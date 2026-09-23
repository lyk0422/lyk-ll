package com.example.starter.rosbridge.service;

import com.example.starter.rosbridge.adapter.RosAdapter;
import com.example.starter.rosbridge.adapter.SyntheticRosAdapter;
import com.example.starter.rosbridge.config.RosbridgeProperties;
import com.example.starter.rosbridge.persistence.GatewayRepository;
import com.example.starter.rosbridge.persistence.GatewayRepository.AcquireResult;
import com.example.starter.rosbridge.persistence.GatewayRepository.ActionGoalRow;
import com.example.starter.rosbridge.persistence.GatewayRepository.ClientSubRow;
import com.example.starter.rosbridge.persistence.GatewayRepository.ReleaseResult;
import com.example.starter.rosbridge.persistence.GatewayRepository.ServiceCallRow;
import com.example.starter.rosbridge.persistence.GatewayRepository.UpstreamRow;
import com.example.starter.rosbridge.protocol.ClientRequest;
import com.example.starter.rosbridge.protocol.Frames;
import com.example.starter.rosbridge.topic.QosNormalizer;
import com.example.starter.rosbridge.topic.QosNormalizer.NormalizedQos;
import com.example.starter.rosbridge.ws.ClientSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 网关核心编排：连接注册、共享订阅引用计数与上游复用、
 * 消息 fan-out、ACK 游标、断连宽限期、Service 超时与 Action 取消/结果幂等。
 *
 * <p>线程模型：上游消息回调来自适配器线程，WebSocket 帧来自容器线程，
 * 两者通过 {@link UpstreamBinding} 内部锁与 {@link ConcurrentHashMap} 协调；
 * 引用计数的权威状态在 H2 事务内维护，内存 binding 仅跟踪句柄与在线路由。
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private static final String SERVICE_OK = "OK";
    private static final String SERVICE_FAILED = "FAILED";
    private static final String SERVICE_TIMED_OUT = "TIMED_OUT";
    private static final String GOAL_PENDING = "PENDING";
    private static final String GOAL_CANCELING = "CANCELING";
    private static final String GOAL_OK = "OK";
    private static final String GOAL_CANCELED = "CANCELED";

    private final RosAdapter rosAdapter;
    private final GatewayRepository repository;
    private final ObjectMapper mapper;
    private final QosNormalizer qosNormalizer;
    private final ScheduledExecutorService scheduler;
    private final RosbridgeProperties properties;
    private final java.time.Clock clock;

    /**
     * 串行化订阅获取/取消/断连过期中的“DB 引用计数 + 内存绑定”复合变更，
     * 避免最后取消释放与新订阅复用交错造成的路由成员丢失。消息 fan-out 不经过此锁。
     */
    private final Object topicLock = new Object();

    /** clientId -> 在线会话；断连宽限期内不存在映射。 */
    private final Map<String, ClientSession> online = new ConcurrentHashMap<>();
    /** clientId -> 断连宽限期到期任务。 */
    private final Map<String, ScheduledFuture<?>> disconnectTimers = new ConcurrentHashMap<>();
    /** upstreamId -> 内存上游绑定（适配器句柄 + 当前活跃成员）。 */
    private final Map<Long, UpstreamBinding> bindings = new ConcurrentHashMap<>();
    /** requestId -> Service 超时任务。 */
    private final Map<String, ScheduledFuture<?>> serviceTimeouts = new ConcurrentHashMap<>();
    /** requestId -> Service 元信息（完成时定位客户端）。 */
    private final Map<String, ServiceMeta> serviceMeta = new ConcurrentHashMap<>();
    /** goalId -> goal 运行时信息。 */
    private final Map<String, GoalRuntime> goals = new ConcurrentHashMap<>();

    public GatewayService(RosAdapter rosAdapter, GatewayRepository repository,
                          ObjectMapper mapper, QosNormalizer qosNormalizer,
                          @Qualifier("gatewayScheduler") ScheduledExecutorService scheduler,
                          RosbridgeProperties properties, java.time.Clock clock) {
        this.rosAdapter = rosAdapter;
        this.repository = repository;
        this.mapper = mapper;
        this.qosNormalizer = qosNormalizer;
        this.scheduler = scheduler;
        this.properties = properties;
        this.clock = clock;
    }

    // ---- 连接生命周期 ----

    /**
     * WebSocket 连接建立：登记/更新客户端配置，取消断连宽限期任务，
     * 创建会话并从 H2 中该客户端各活跃订阅的确认游标恢复投递（续投未确认消息）。
     */
    public ClientSession registerConnection(WebSocketSession wsSession, String clientId,
                                            int rateLimit, int queueCapacity) {
        long now = clock.millis();
        repository.upsertClientOnConnect(clientId, rateLimit, queueCapacity, now);

        ScheduledFuture<?> timer = disconnectTimers.remove(clientId);
        if (timer != null) {
            timer.cancel(false);
        }

        long resumeAck = repository.findActiveSubscriptions(clientId).stream()
                .mapToLong(ClientSubRow::ackCursor).max().orElse(0L);

        ClientSession session = new ClientSession(clientId, wsSession, rateLimit,
                queueCapacity, resumeAck, mapper, scheduler,
                () -> java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(clock.millis()));
        ClientSession previous = online.put(clientId, session);
        if (previous != null && previous != session) {
            log.info("客户端 {} 建立新连接，关闭其旧连接", clientId);
            previous.close();
        }

        // 重连：逐个活跃订阅从“大于确认游标”的可用消息继续，不重复已确认内容。
        for (ClientSubRow sub : repository.findActiveSubscriptions(clientId)) {
            replayAvailable(clientId, session, sub);
        }
        return session;
    }

    /**
     * 连接断开：会话下线但订阅保留宽限期；到期后停用该客户端全部活跃订阅、
     * 递减引用计数并释放归零的上游资源。
     *
     * <p>若该 clientId 已建立更新的连接（旧连接关闭事件延迟到达），本次忽略，
     * 避免旧连接的关闭事件把新连接误下线。
     */
    public void unregisterConnection(String clientId, WebSocketSession closedSession) {
        ClientSession current = online.get(clientId);
        if (current != null && closedSession != null
                && !current.getWsSession().getId().equals(closedSession.getId())) {
            log.info("客户端 {} 旧连接 {} 的关闭事件延迟到达，当前已有新连接，忽略",
                    clientId, closedSession.getId());
            return;
        }
        unregisterConnection(clientId);
    }

    /** 不区分具体连接的断连登记（测试与内部使用）。 */
    public void unregisterConnection(String clientId) {
        ClientSession session = online.remove(clientId);
        if (session != null) {
            session.close();
        }
        repository.markClientDisconnected(clientId, clock.millis());

        long graceMs = Math.max(0, properties.getReconnectGraceMs());
        ScheduledFuture<?> task = scheduler.schedule(() -> expireClient(clientId),
                graceMs, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> old = disconnectTimers.put(clientId, task);
        if (old != null) {
            old.cancel(false);
        }
    }

    private void expireClient(String clientId) {
        try {
            synchronized (topicLock) {
                GatewayRepository.ExpireResult result =
                        repository.expireClientSubscriptions(clientId);
                for (Long upstreamId : result.touchedUpstreamIds()) {
                    removeMember(upstreamId, clientId);
                }
                for (Long upstreamId : result.releasedUpstreamIds()) {
                    closeBindingIfEmpty(upstreamId);
                }
            }
            disconnectTimers.remove(clientId);
        } catch (Exception e) {
            log.error("客户端 {} 宽限期到期处理失败", clientId, e);
        }
    }

    // ---- 请求分发 ----

    /** 处理客户端请求帧。 */
    public void handleRequest(String clientId, ClientRequest request) {
        ClientSession session = online.get(clientId);
        if (session == null) {
            log.warn("客户端 {} 已离线，忽略请求 {}", clientId, request.getOp());
            return;
        }
        String op = request.getOp() == null ? "" : request.getOp().trim().toUpperCase();
        try {
            switch (op) {
                case "SUBSCRIBE" -> handleSubscribe(clientId, session, request);
                case "UNSUBSCRIBE" -> handleUnsubscribe(clientId, session, request);
                case "ACK" -> handleAck(clientId, session, request);
                case "SERVICE_CALL" -> handleServiceCall(clientId, session, request);
                case "ACTION_SEND_GOAL" -> handleSendGoal(clientId, session, request);
                case "ACTION_CANCEL" -> handleCancelGoal(clientId, session, request);
                default -> session.sendControl(Frames.error(Frames.CODE_ERROR,
                        "未知操作码: " + request.getOp()));
            }
        } catch (BadRequestException e) {
            session.sendControl(Frames.error(Frames.CODE_ERROR, e.getMessage()));
        } catch (Exception e) {
            log.error("客户端 {} 请求 {} 处理失败", clientId, op, e);
            session.sendControl(Frames.error(Frames.CODE_ERROR, "内部错误"));
        }
    }

    // ---- Topic 订阅 ----

    private void handleSubscribe(String clientId, ClientSession session, ClientRequest request) {
        String topic = requireText(request.getTopic(), "topic");
        String type = requireText(request.getType(), "type");
        NormalizedQos qos = qosNormalizer.normalize(request.getQos());
        long now = clock.millis();

        synchronized (topicLock) {
            AcquireResult acquired = repository.acquireSubscription(
                    clientId, topic, type, qos.hash(), qos.canonicalJson(), now);

            bindings.compute(acquired.upstreamId(), (id, existing) -> {
                if (existing != null) {
                    synchronized (existing) {
                        existing.members.add(clientId);
                    }
                    return existing;
                }
                UpstreamBinding created = new UpstreamBinding(id, topic, type, qos.valueMap());
                created.members.add(clientId);
                created.handle = rosAdapter.subscribeTopic(topic, type, qos.valueMap(),
                        message -> routeUpstreamMessage(id, topic, type, message));
                return created;
            });

            ClientSubRow sub = repository.findActiveSubscriptions(clientId).stream()
                    .filter(s -> s.upstreamId() == acquired.upstreamId())
                    .findFirst().orElseThrow(() -> new IllegalStateException("订阅激活后未找到行"));

            // 新订阅水位为订阅时刻最新游标（不回放历史）；重连/重复订阅按已确认游标续投。
            replayAvailable(clientId, session, sub, sub.ackCursor());
        }

        session.sendControl(Frames.subscribed(topic));
        log.debug("客户端 {} 订阅 {}/{} 完成", clientId, topic, type);
    }

    private void handleUnsubscribe(String clientId, ClientSession session, ClientRequest request) {
        String topic = requireText(request.getTopic(), "topic");
        synchronized (topicLock) {
            for (ClientSubRow sub : repository.findActiveSubscriptions(clientId)) {
                if (!topic.equals(sub.topic())) {
                    continue;
                }
                ReleaseResult result = repository.releaseSubscription(clientId, sub.upstreamId());
                if (result.deactivated()) {
                    removeMember(sub.upstreamId(), clientId);
                    if (result.released()) {
                        closeBindingIfEmpty(sub.upstreamId());
                    }
                }
            }
        }
        // 取消幂等：重复取消（含从未订阅）同样返回 UNSUBSCRIBED。
        session.sendControl(Frames.unsubscribed(topic));
    }

    private void handleAck(String clientId, ClientSession session, ClientRequest request) {
        if (request.getCursor() == null || request.getCursor() < 0) {
            throw new BadRequestException("ACK 缺少合法 cursor");
        }
        long cursor = request.getCursor();
        session.ack(cursor);
        repository.advanceAck(clientId, cursor);
    }

    /**
     * 上游消息到达：分配全局单调游标、落 H2，并向该绑定的全部在线成员 fan-out。
     * 离线成员（宽限期内）不投递，重连后依据其确认游标从 H2 续投。
     */
    private void routeUpstreamMessage(long upstreamId, String topic, String type, JsonNode message) {
        UpstreamBinding binding = bindings.get(upstreamId);
        if (binding == null) {
            return;
        }
        String payload;
        try {
            payload = mapper.writeValueAsString(message);
        } catch (Exception e) {
            log.warn("upstream {} 消息序列化失败: {}", upstreamId, e.toString());
            return;
        }
        long cursor = repository.appendMessage(upstreamId, topic, type, payload,
                clock.millis(), properties.getMessageRetentionPerTopic());
        Set<String> members;
        synchronized (binding) {
            members = new HashSet<>(binding.members);
        }
        String frameText;
        try {
            frameText = mapper.writeValueAsString(Frames.publish(topic, type, cursor, message));
        } catch (Exception e) {
            log.warn("upstream {} PUBLISH 帧序列化失败: {}", upstreamId, e.toString());
            return;
        }
        for (String member : members) {
            ClientSession session = online.get(member);
            if (session != null) {
                session.offerData(cursor, frameText);
            }
        }
    }

    private void replayAvailable(String clientId, ClientSession session, ClientSubRow sub) {
        replayAvailable(clientId, session, sub, sub.ackCursor());
    }

    /** 从 H2 中读取大于确认游标的可用消息并直接进入该客户端发送队列。 */
    private void replayAvailable(String clientId, ClientSession session, ClientSubRow sub,
                                 long afterCursor) {
        List<GatewayRepository.MessageRow> rows =
                repository.findMessagesAfter(sub.upstreamId(), afterCursor,
                        properties.getMessageRetentionPerTopic());
        for (GatewayRepository.MessageRow row : rows) {
            try {
                JsonNode payload = mapper.readTree(row.payload());
                String frameText = mapper.writeValueAsString(Frames.publish(
                        row.topic(), row.msgType(), row.cursor(), payload));
                session.offerData(row.cursor(), frameText);
            } catch (Exception e) {
                log.warn("客户端 {} 重放消息游标 {} 失败: {}", clientId, row.cursor(), e.toString());
            }
        }
    }

    private void removeMember(long upstreamId, String clientId) {
        UpstreamBinding binding = bindings.get(upstreamId);
        if (binding == null) {
            return;
        }
        synchronized (binding) {
            binding.members.remove(clientId);
        }
    }

    /** 仅当绑定无活跃成员时关闭适配器上游句柄并移除绑定（最后取消才释放上游资源）。 */
    private void closeBindingIfEmpty(long upstreamId) {
        UpstreamBinding binding = bindings.get(upstreamId);
        if (binding == null) {
            return;
        }
        boolean empty;
        synchronized (binding) {
            empty = binding.members.isEmpty();
            if (empty) {
                bindings.remove(upstreamId, binding);
            }
        }
        if (empty) {
            try {
                if (binding.handle != null) {
                    binding.handle.close();
                }
            } catch (Exception e) {
                log.warn("关闭上游 {} 句柄异常: {}", upstreamId, e.toString());
            }
        }
    }

    // ---- Service ----

    private void handleServiceCall(String clientId, ClientSession session, ClientRequest request) {
        String requestId = requireText(request.getRequestId(), "requestId");
        String service = requireText(request.getService(), "service");
        long now = clock.millis();

        GatewayRepository.ServiceCallInsert insert = repository.insertServiceCallIfAbsent(
                requestId, clientId, service, now);
        if (!insert.inserted()) {
            // 同 requestId 重发：已终态则重放最终帧，仍在途则幂等忽略。
            resendFinishedService(session, service, insert.row());
            return;
        }

        serviceMeta.put(requestId, new ServiceMeta(clientId, service));
        ScheduledFuture<?> timeoutTask = scheduler.schedule(
                () -> finishServiceByTimeout(requestId),
                Math.max(1, properties.getServiceTimeoutMs()), TimeUnit.MILLISECONDS);
        serviceTimeouts.put(requestId, timeoutTask);

        rosAdapter.callService(service, request.getArgs(), new RosAdapter.ServiceCallback() {
            @Override
            public void onSuccess(JsonNode result) {
                String resultText = writeJsonOrEmpty(result);
                if (repository.completeServiceCall(requestId, SERVICE_OK, null, null,
                        resultText, clock.millis())) {
                    cancelTimeout(requestId);
                    sendToClient(clientId,
                            Frames.serviceResponse(requestId, service, result));
                }
                // CAS 失败说明超时已终结请求，迟到结果不再完成请求。
            }

            @Override
            public void onFailure(String code, String message) {
                if (repository.completeServiceCall(requestId, SERVICE_FAILED,
                        code == null ? Frames.CODE_ERROR : code, message, null,
                        clock.millis())) {
                    cancelTimeout(requestId);
                    sendToClient(clientId, Frames.serviceError(requestId, service,
                            code == null ? Frames.CODE_ERROR : code, message));
                }
            }
        });
    }

    private void finishServiceByTimeout(String requestId) {
        ServiceMeta meta = serviceMeta.get(requestId);
        if (meta == null) {
            return;
        }
        if (repository.completeServiceCall(requestId, SERVICE_TIMED_OUT, Frames.CODE_TIMEOUT,
                "Service 调用超时", null, clock.millis())) {
            serviceTimeouts.remove(requestId);
            serviceMeta.remove(requestId);
            sendToClient(meta.clientId(), Frames.serviceError(requestId, meta.service(),
                    Frames.CODE_TIMEOUT, "Service 调用超时"));
        }
    }

    private void cancelTimeout(String requestId) {
        ScheduledFuture<?> task = serviceTimeouts.remove(requestId);
        if (task != null) {
            task.cancel(false);
        }
        serviceMeta.remove(requestId);
    }

    private void resendFinishedService(ClientSession session, String service, ServiceCallRow row) {
        switch (row.status()) {
            case SERVICE_OK -> {
                JsonNode result = readJsonOrNull(row.resultText());
                session.sendControl(Frames.serviceResponse(row.requestId(), service, result));
            }
            case SERVICE_TIMED_OUT -> session.sendControl(Frames.serviceError(
                    row.requestId(), service, Frames.CODE_TIMEOUT, row.errorMessage()));
            case SERVICE_FAILED -> session.sendControl(Frames.serviceError(
                    row.requestId(), service,
                    row.errorCode() == null ? Frames.CODE_ERROR : row.errorCode(),
                    row.errorMessage()));
            default -> {
                // PENDING：同一请求仍在途，忽略重复帧。
            }
        }
    }

    // ---- Action ----

    private void handleSendGoal(String clientId, ClientSession session, ClientRequest request) {
        String goalId = requireText(request.getGoalId(), "goalId");
        String action = requireText(request.getAction(), "action");
        long now = clock.millis();

        GatewayRepository.GoalInsert insert =
                repository.insertGoalIfAbsent(goalId, clientId, action, now);
        if (!insert.inserted()) {
            // goalId 重复：已终态则重放最终结果，否则幂等忽略。
            resendFinishedGoal(session, action, insert.row());
            return;
        }

        GoalRuntime runtime = new GoalRuntime(clientId, action);
        goals.put(goalId, runtime);
        runtime.handle = rosAdapter.sendGoal(action, request.getArgs(), new RosAdapter.GoalListener() {
            @Override
            public void onFeedback(JsonNode feedback) {
                ActionGoalRow row = repository.findGoal(goalId);
                // 已请求取消或已终态的 goal 不再下发进度反馈。
                if (row == null || !GOAL_PENDING.equals(row.status())) {
                    return;
                }
                sendToClient(runtime.clientId,
                        Frames.actionFeedback(goalId, action, feedback));
            }

            @Override
            public void onResult(RosAdapter.ActionOutcome outcome) {
                ActionGoalRow row = repository.findGoal(goalId);
                String finalStatus;
                if (row != null && GOAL_CANCELING.equals(row.status())) {
                    finalStatus = GOAL_CANCELED;
                } else {
                    finalStatus = Frames.ACTION_CANCELED.equals(outcome.status())
                            ? GOAL_CANCELED : GOAL_OK;
                }
                String resultText = writeJsonOrEmpty(outcome.result());
                if (repository.completeGoal(goalId, finalStatus, resultText, clock.millis())) {
                    goals.remove(goalId);
                    sendToClient(runtime.clientId, Frames.actionResult(goalId, action,
                            finalStatus, outcome.result()));
                }
                // 已有最终结果时，迟到结果不再下发。
            }
        });
    }

    private void handleCancelGoal(String clientId, ClientSession session, ClientRequest request) {
        String goalId = requireText(request.getGoalId(), "goalId");
        ActionGoalRow row = repository.findGoal(goalId);
        if (row == null) {
            session.sendControl(Frames.error(Frames.CODE_ERROR, "未知 goalId: " + goalId));
            return;
        }
        if (repository.markGoalCanceling(goalId, clock.millis())) {
            GoalRuntime runtime = goals.get(goalId);
            if (runtime != null && runtime.handle != null) {
                runtime.handle.cancel();
            }
        }
        // 重复取消或已终态均为幂等无操作；最终结果以 ACTION_RESULT 为准。
    }

    private void resendFinishedGoal(ClientSession session, String action, ActionGoalRow row) {
        if (!isGoalTerminal(row.status())) {
            return;
        }
        JsonNode result = readJsonOrNull(row.resultText());
        session.sendControl(Frames.actionResult(row.goalId(), action, row.status(), result));
    }

    private static boolean isGoalTerminal(String status) {
        return GOAL_OK.equals(status) || GOAL_CANCELED.equals(status);
    }

    // ---- 辅助 ----

    private void sendToClient(String clientId, Map<String, Object> frame) {
        ClientSession session = online.get(clientId);
        if (session != null) {
            session.sendControl(frame);
        }
    }

    private String writeJsonOrEmpty(JsonNode node) {
        try {
            return node == null ? "{}" : mapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    private JsonNode readJsonOrNull(String text) {
        try {
            return text == null ? mapper.createObjectNode() : mapper.readTree(text);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("缺少必填字段: " + field);
        }
        return value;
    }

    /** 当前在线客户端数（测试用）。 */
    public int onlineClientCount() {
        return online.size();
    }

    /** 某 Topic 当前真实上游订阅数（适配器为合成实现时可用，测试引用计数）。 */
    public int upstreamCount(String topic) {
        if (rosAdapter instanceof SyntheticRosAdapter synthetic) {
            return synthetic.upstreamSubscriptionCount(topic);
        }
        return -1;
    }

    private static final class BadRequestException extends RuntimeException {
        BadRequestException(String message) {
            super(message);
        }
    }

    /** 单个共享上游订阅的运行时绑定。 */
    private static final class UpstreamBinding {
        private final long upstreamId;
        private final String topic;
        private final String type;
        private final Map<String, Object> qos;
        private final Set<String> members = ConcurrentHashMap.newKeySet();
        private volatile RosAdapter.TopicHandle handle;

        private UpstreamBinding(long upstreamId, String topic, String type, Map<String, Object> qos) {
            this.upstreamId = upstreamId;
            this.topic = topic;
            this.type = type;
            this.qos = qos;
        }
    }

    private record ServiceMeta(String clientId, String service) {
    }

    private static final class GoalRuntime {
        private final String clientId;
        private final String action;
        private volatile RosAdapter.GoalHandle handle;

        private GoalRuntime(String clientId, String action) {
            this.clientId = clientId;
            this.action = action;
        }
    }
}
