package com.example.starter.rosbridge.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.starter.rosbridge.config.RosBridgeProperties;
import com.example.starter.rosbridge.protocol.CallStatus;
import com.example.starter.rosbridge.protocol.Frames;
import com.example.starter.rosbridge.protocol.GoalStatus;
import com.example.starter.rosbridge.protocol.Op;
import com.example.starter.rosbridge.protocol.ProtocolJson;
import com.example.starter.rosbridge.ros.RosAdapter;
import com.example.starter.rosbridge.ros.RosGoalHandle;
import com.example.starter.rosbridge.ros.RosSubscription;
import com.example.starter.rosbridge.ros.SubscriptionKey;
import com.example.starter.rosbridge.store.GatewayRepository;
import com.example.starter.rosbridge.time.TimeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * 网关核心：协议处理、上游订阅引用计数与宽限释放、单调游标分配与扇出、
 * 重连补发、Service 超时与 Action 终态幂等。
 */
@Component
public class GatewayCore {

    private static final Logger log = LoggerFactory.getLogger(GatewayCore.class);

    private final GatewayRepository repository;
    private final RosAdapter rosAdapter;
    private final RosBridgeProperties properties;
    private final TimeSource timeSource;
    private final BoundedMessageLedger ledger;
    private final SessionFactory sessionFactory;
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "rosbridge-scheduler");
                t.setDaemon(true);
                return t;
            });

    /** clientId -> 在线上下文（同 clientId 新连接顶掉旧连接）。 */
    private final Map<String, ClientContext> clients = new ConcurrentHashMap<>();
    /** 共享订阅 -> 运行态（含上游句柄与宽限释放任务）。 */
    private final Map<SubscriptionKey, SharedSubscription> sharedSubscriptions = new ConcurrentHashMap<>();
    /** service 调用 -> 超时任务，结果先到时取消。 */
    private final Map<String, ScheduledFuture<?>> timeoutTasks = new ConcurrentHashMap<>();

    public GatewayCore(GatewayRepository repository, RosAdapter rosAdapter,
                       RosBridgeProperties properties, TimeSource timeSource,
                       BoundedMessageLedger ledger, SessionFactory sessionFactory) {
        this.repository = repository;
        this.rosAdapter = rosAdapter;
        this.properties = properties;
        this.timeSource = timeSource;
        this.ledger = ledger;
        this.sessionFactory = sessionFactory;
    }

    // ---------------- 连接生命周期 ----------------

    /**
     * 建立（或重连）客户端会话。同 clientId 已有在线连接时，旧连接按断连处理（退订进入宽限期）。
     * 返回本次连接的 sessionId。
     */
    public String connect(String clientId, String sessionId, WebSocketSession wsSession,
                          Integer rateOverride, Integer capacityOverride) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId required");
        }
        ClientContext old = clients.get(clientId);
        if (old != null) {
            log.info("client={} replaced old session={}", clientId, old.sessionId);
            cleanupClient(old);
        }
        int rate = rateOverride != null && rateOverride >= 0 ? rateOverride : properties.defaultRatePerSecond();
        int cap = capacityOverride != null && capacityOverride > 0
                ? capacityOverride : properties.defaultQueueCapacity();
        ClientSession session = sessionFactory.create(clientId, wsSession, rate, cap);
        ClientContext ctx = new ClientContext(clientId, sessionId, session, rate, cap);
        clients.put(clientId, ctx);
        repository.openConnection(clientId, sessionId, timeSource.nowMillis());
        session.enqueue(OutboundFrame.control(Frames.connack(clientId, sessionId, rate, cap)));
        return sessionId;
    }

    /** 断开连接；仅当 sessionId 与当前在线会话匹配时生效（顶号竞态下忽略旧连接关闭事件）。 */
    public void disconnect(String clientId, String sessionId) {
        ClientContext ctx = clients.get(clientId);
        if (ctx == null || !ctx.sessionId.equals(sessionId)) {
            return;
        }
        clients.remove(clientId, ctx);
        cleanupClient(ctx);
        repository.closeConnection(sessionId, timeSource.nowMillis());
    }

    /**
     * 下线一个客户端上下文：批量退订（最后引用归零者进入宽限期）并关闭发送会话。
     * 客户端订阅行删除，但 ACK 游标保留，供同 JVM 重连补发。
     */
    private void cleanupClient(ClientContext ctx) {
        for (SubscriptionKey key : repository.listClientSubscriptions(ctx.clientId)) {
            releaseOneRef(ctx.clientId, key);
            repository.deleteClientSubscription(ctx.clientId, key);
        }
        ctx.session.close();
    }

    // ---------------- 协议入口 ----------------

    public void handleText(String clientId, String payload) {
        JsonNode node;
        try {
            node = ProtocolJson.MAPPER.readTree(payload);
        } catch (Exception e) {
            sendControl(clientId, Frames.error(null, "BAD_FRAME", "payload must be valid JSON"));
            return;
        }
        String op = node.path("op").asText(null);
        if (op == null) {
            sendControl(clientId, Frames.error(null, "BAD_FRAME", "missing op"));
            return;
        }
        try {
            switch (op) {
                case Op.SUBSCRIBE -> handleSubscribe(clientId, node);
                case Op.UNSUBSCRIBE -> handleUnsubscribe(clientId, node);
                case Op.ACK -> handleAck(clientId, node);
                case Op.CALL_SERVICE -> handleCallService(clientId, node);
                case Op.SEND_GOAL -> handleSendGoal(clientId, node);
                case Op.CANCEL_GOAL -> handleCancelGoal(clientId, node);
                default -> sendControl(clientId, Frames.error(op, "UNKNOWN_OP", "unsupported op: " + op));
            }
        } catch (Exception e) {
            log.warn("client={} op={} failed: {}", clientId, op, e.toString());
            sendControl(clientId, Frames.error(op, "INTERNAL_ERROR", e.getMessage()));
        }
    }

    // ---------------- Topic 订阅 ----------------

    private void handleSubscribe(String clientId, JsonNode node) {
        SubscriptionKey key = parseKey(node);
        long now = timeSource.nowMillis();
        ClientContext ctx = requireClient(clientId);

        boolean isNew = repository.insertClientSubscriptionIfAbsent(clientId, key, ctx.sessionId, now);
        if (isNew) {
            repository.ensureSubscriptionRef(key);
            int rc = repository.incrementRefcount(key);
            acquireSharedSubscription(key, rc == 1);
        }
        // 重复订阅幂等：直接回 SUBSCRIBED，引用计数不变
        sendControl(clientId, Frames.subscribed(key.topic(), key.messageType(), key.qosJson(), ProtocolJson.MAPPER));
        replayAfterAck(clientId, key);
    }

    private void handleUnsubscribe(String clientId, JsonNode node) {
        String topic = node.path("topic").asText(null);
        if (topic == null || topic.isBlank()) {
            sendControl(clientId, Frames.error(Op.UNSUBSCRIBE, "BAD_FRAME", "topic required"));
            return;
        }
        // UNSUBSCRIBE 通常只带 topic：释放该客户端在此 topic 上的全部（不同 type/qos）订阅；
        // 帧内显式带 type/qos 时只释放精确匹配的一个
        if (node.has("type")) {
            SubscriptionKey key = parseKey(node);
            if (repository.deleteClientSubscription(clientId, key)) {
                releaseOneRef(clientId, key);
            }
        } else {
            for (SubscriptionKey key : repository.listClientSubscriptions(clientId)) {
                if (key.topic().equals(topic) && repository.deleteClientSubscription(clientId, key)) {
                    releaseOneRef(clientId, key);
                }
            }
        }
        // 重复取消幂等：无论是否存在均回 UNSUBSCRIBED
        sendControl(clientId, Frames.unsubscribed(topic));
    }

    /** 释放一个客户端引用；引用计数归零则安排宽限结束后真正释放上游。 */
    private void releaseOneRef(String clientId, SubscriptionKey key) {
        int rc = repository.decrementRefcount(key, timeSource.nowMillis() + properties.gracePeriodMs());
        if (rc == 0) {
            scheduleGraceRelease(key);
        }
    }

    /**
     * 确保共享订阅运行态存在并按需建立上游订阅（原子）。
     * 宽限期内重新订阅：引用计数 0->1，取消待执行的释放任务并复用既有上游；
     * 并发首次订阅只会有一个线程真正调用适配器建立上游。
     */
    private void acquireSharedSubscription(SubscriptionKey key, boolean wasZero) {
        sharedSubscriptions.compute(key, (k, existing) -> {
            if (existing != null) {
                if (existing.graceTask != null) {
                    existing.graceTask.cancel(false);
                    existing.graceTask = null;
                }
                return existing;
            }
            RosSubscription rosSubscription = rosAdapter.subscribe(k, data -> onUpstreamMessage(k, data));
            return new SharedSubscription(k, rosSubscription);
        });
    }

    private void scheduleGraceRelease(SubscriptionKey key) {
        long delay = Math.max(0, properties.gracePeriodMs());
        sharedSubscriptions.computeIfPresent(key, (k, shared) -> {
            shared.graceTask = scheduler.schedule(() -> releaseUpstreamIfUnreferenced(key),
                    delay, TimeUnit.MILLISECONDS);
            return shared;
        });
    }

    /**
     * 宽限结束：与 {@link #acquireSharedSubscription} 在同一 map 原子操作内竞争，
     * 仅当引用计数仍为 0（期间无重新订阅）才真正移除并释放上游资源。
     */
    private void releaseUpstreamIfUnreferenced(SubscriptionKey key) {
        SharedSubscription[] removed = new SharedSubscription[1];
        sharedSubscriptions.compute(key, (k, existing) -> {
            if (existing == null || repository.getRefcount(k) > 0) {
                return existing;
            }
            removed[0] = existing;
            return null;
        });
        SharedSubscription shared = removed[0];
        if (shared == null) {
            return;
        }
        try {
            shared.rosSubscription.close();
        } catch (Exception e) {
            log.warn("close upstream subscription failed: {}", e.toString());
        }
        repository.deleteRefIfZero(key);
        ledger.remove(key);
        log.info("upstream subscription released after grace: {}", key);
    }

    /** 上游消息：分配共享单调游标、入补发账本、扇出给各在线订阅者。 */
    private void onUpstreamMessage(SubscriptionKey key, JsonNode data) {
        long cursor = repository.nextCursor(key);
        ledger.append(key, cursor, key.messageType(), data);
        String payload = Frames.topic(key.topic(), key.messageType(), cursor, data, false);
        for (String subscriberClientId : repository.listSubscribers(key)) {
            ClientContext ctx = clients.get(subscriberClientId);
            if (ctx != null) {
                ctx.session.enqueue(OutboundFrame.topic(payload, key.topic(), cursor));
            }
        }
    }

    /**
     * 重连补发：仅当存在历史 ACK 游标时，从严格大于该游标的可用消息继续，
     * 不重复已确认消息；首次订阅（无 ACK 记录）不回放历史消息。
     */
    private void replayAfterAck(String clientId, SubscriptionKey key) {
        Long ack = repository.queryAck(clientId, key);
        if (ack == null) {
            return;
        }
        List<MessageLedger.LedgerEntry> missed = ledger.after(key, ack);
        ClientContext ctx = clients.get(clientId);
        if (ctx == null) {
            return;
        }
        for (MessageLedger.LedgerEntry entry : missed) {
            String payload = Frames.topic(key.topic(), entry.type(), entry.cursor(), entry.data(), true);
            ctx.session.enqueue(OutboundFrame.topic(payload, key.topic(), entry.cursor()));
        }
    }

    // ---------------- ACK ----------------

    private void handleAck(String clientId, JsonNode node) {
        String topic = node.path("topic").asText(null);
        long cursor = node.path("cursor").asLong(0);
        if (topic == null || cursor <= 0) {
            sendControl(clientId, Frames.error(Op.ACK, "BAD_FRAME", "topic and positive cursor required"));
            return;
        }
        long now = timeSource.nowMillis();
        List<SubscriptionKey> keys = resolveKeys(clientId, topic, node);
        for (SubscriptionKey key : keys) {
            repository.ackCursor(clientId, key, cursor, now);
        }
        ClientContext ctx = clients.get(clientId);
        if (ctx != null) {
            ctx.session.acknowledge(topic, cursor);
        }
    }

    /** ACK 目标键：帧内显式 type/qos 优先；否则取该客户端在该 topic 的订阅；再否则取已知游标键。 */
    private List<SubscriptionKey> resolveKeys(String clientId, String topic, JsonNode node) {
        if (node.has("type")) {
            return List.of(parseKey(node));
        }
        List<SubscriptionKey> keys = new ArrayList<>();
        for (SubscriptionKey key : repository.listClientSubscriptions(clientId)) {
            if (key.topic().equals(topic)) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            for (SubscriptionKey key : repository.listCursorKeysForTopic(topic)) {
                keys.add(key);
            }
        }
        return keys;
    }

    // ---------------- Service ----------------

    private void handleCallService(String clientId, JsonNode node) {
        String requestId = node.path("requestId").asText(null);
        String service = node.path("service").asText(null);
        if (requestId == null || service == null) {
            sendControl(clientId, Frames.error(Op.CALL_SERVICE, "BAD_FRAME", "requestId and service required"));
            return;
        }
        String type = node.has("type") ? node.get("type").asText() : null;
        JsonNode args = node.get("args");
        long now = timeSource.nowMillis();

        // 同 (clientId,requestId) 幂等：重复帧不再次发起调用
        if (!repository.insertCallIfAbsent(clientId, requestId, service, now)) {
            log.debug("duplicate service call client={} requestId={} ignored", clientId, requestId);
            return;
        }

        String timeoutKey = clientId + ' ' + requestId;
        ScheduledFuture<?> timeout = scheduler.schedule(() -> {
            if (repository.casCall(clientId, requestId, CallStatus.TIMEOUT.name(), null, null, timeSource.nowMillis())) {
                sendControl(clientId, Frames.serviceResponse(requestId, service, CallStatus.TIMEOUT, null, "timeout"));
            }
            timeoutTasks.remove(timeoutKey);
        }, Math.max(1, properties.serviceTimeoutMs()), TimeUnit.MILLISECONDS);
        timeoutTasks.put(timeoutKey, timeout);

        CompletableFuture<JsonNode> future = rosAdapter.callService(service, type, args);
        future.whenComplete((result, error) -> {
            ScheduledFuture<?> t = timeoutTasks.remove(timeoutKey);
            if (t != null) {
                t.cancel(false);
            }
            if (error != null) {
                if (repository.casCall(clientId, requestId, CallStatus.FAILED.name(), null,
                        String.valueOf(error.getMessage()), timeSource.nowMillis())) {
                    sendControl(clientId, Frames.serviceResponse(requestId, service, CallStatus.FAILED,
                            null, String.valueOf(error.getMessage())));
                }
            } else if (repository.casCall(clientId, requestId, CallStatus.COMPLETED.name(),
                    result == null ? null : result.toString(), null, timeSource.nowMillis())) {
                // CAS 成功说明未超时；迟到结果在超时后 CAS 失败，不会再次完成请求
                sendControl(clientId, Frames.serviceResponse(requestId, service, CallStatus.COMPLETED, result, null));
            }
        });
    }

    // ---------------- Action ----------------

    private void handleSendGoal(String clientId, JsonNode node) {
        String goalId = node.path("goalId").asText(null);
        String action = node.path("action").asText(null);
        if (goalId == null || action == null) {
            sendControl(clientId, Frames.error(Op.SEND_GOAL, "BAD_FRAME", "goalId and action required"));
            return;
        }
        String type = node.has("type") ? node.get("type").asText() : null;
        JsonNode args = node.get("args");
        long now = timeSource.nowMillis();

        // 同 (clientId,goalId) 幂等：重复提交不再次执行
        if (!repository.insertGoalIfAbsent(clientId, goalId, action, now)) {
            log.debug("duplicate goal client={} goalId={} ignored", clientId, goalId);
            return;
        }
        GoalHandleImpl handle = new GoalHandleImpl(clientId, goalId, action);
        rosAdapter.sendGoal(action, type, args, handle);
        sendControl(clientId, Frames.goalAccepted(goalId, action));
    }

    private void handleCancelGoal(String clientId, JsonNode node) {
        String goalId = node.path("goalId").asText(null);
        if (goalId == null) {
            sendControl(clientId, Frames.error(Op.CANCEL_GOAL, "BAD_FRAME", "goalId required"));
            return;
        }
        String action = node.has("action") ? node.get("action").asText() : null;
        // PENDING -> CANCELLED 比较交换：取消成功即终态；已终态 goal 的重复取消被忽略（幂等）
        if (repository.casGoal(clientId, goalId, GoalStatus.CANCELLED.name(),
                null, null, timeSource.nowMillis())) {
            sendControl(clientId, Frames.actionResult(goalId, action, GoalStatus.CANCELLED, null, null));
            // 通知适配器释放上游；其随后迟到的 cancelled/result 终态会被 CAS 拦截
            rosAdapter.cancelGoal(action, goalId);
        }
    }

    /** Action 适配器回调实现；所有终态经数据库 CAS 去重，取消与迟到最终结果互相幂等。 */
    final class GoalHandleImpl implements RosGoalHandle {

        private final String clientId;
        private final String goalId;
        private final String action;

        GoalHandleImpl(String clientId, String goalId, String action) {
            this.clientId = clientId;
            this.goalId = goalId;
            this.action = action;
        }

        @Override
        public String goalToken() {
            return goalId;
        }

        @Override
        public void publishFeedback(JsonNode feedback) {
            sendControl(clientId, Frames.actionFeedback(goalId, action, feedback));
        }

        @Override
        public void succeed(JsonNode result) {
            if (repository.casGoal(clientId, goalId, GoalStatus.SUCCEEDED.name(),
                    result == null ? null : result.toString(), null, timeSource.nowMillis())) {
                sendControl(clientId, Frames.actionResult(goalId, action, GoalStatus.SUCCEEDED, result, null));
            }
        }

        @Override
        public void fail(String errorMessage, JsonNode result) {
            if (repository.casGoal(clientId, goalId, GoalStatus.FAILED.name(),
                    result == null ? null : result.toString(), errorMessage, timeSource.nowMillis())) {
                sendControl(clientId, Frames.actionResult(goalId, action, GoalStatus.FAILED, result, errorMessage));
            }
        }

        @Override
        public void cancelled(JsonNode result) {
            if (repository.casGoal(clientId, goalId, GoalStatus.CANCELLED.name(),
                    result == null ? null : result.toString(), null, timeSource.nowMillis())) {
                sendControl(clientId, Frames.actionResult(goalId, action, GoalStatus.CANCELLED, result, null));
            }
        }
    }

    // ---------------- 辅助 ----------------

    private SubscriptionKey parseKey(JsonNode node) {
        String topic = node.path("topic").asText(null);
        String type = node.has("type") ? node.get("type").asText() : "";
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic required");
        }
        String qos = SubscriptionKey.normalizeQos(ProtocolJson.MAPPER, node.get("qos"));
        return new SubscriptionKey(topic, type, qos);
    }

    private ClientContext requireClient(String clientId) {
        ClientContext ctx = clients.get(clientId);
        if (ctx == null) {
            throw new IllegalStateException("client not connected: " + clientId);
        }
        return ctx;
    }

    private void sendControl(String clientId, String payload) {
        ClientContext ctx = clients.get(clientId);
        if (ctx != null) {
            ctx.session.enqueue(OutboundFrame.control(payload));
        }
    }

    /** 生成新的连接会话标识。 */
    public static String newSessionId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 测试隔离：关闭全部客户端会话并立即释放全部上游订阅与运行态，
     * 配合仓库 deleteAll 与账本 clear 使用。
     */
    public void releaseAllForTesting() {
        clients.values().forEach(c -> c.session.close());
        clients.clear();
        sharedSubscriptions.values().forEach(s -> {
            if (s.graceTask != null) {
                s.graceTask.cancel(false);
            }
            try {
                s.rosSubscription.close();
            } catch (Exception e) {
                log.warn("close upstream subscription failed during reset: {}", e.toString());
            }
        });
        sharedSubscriptions.clear();
        timeoutTasks.values().forEach(t -> t.cancel(false));
        timeoutTasks.clear();
    }

    /** 单个客户端在线上下文。 */
    static final class ClientContext {
        final String clientId;
        final String sessionId;
        final ClientSession session;
        final int ratePerSecond;
        final int queueCapacity;

        ClientContext(String clientId, String sessionId, ClientSession session,
                      int ratePerSecond, int queueCapacity) {
            this.clientId = clientId;
            this.sessionId = sessionId;
            this.session = session;
            this.ratePerSecond = ratePerSecond;
            this.queueCapacity = queueCapacity;
        }
    }

    /** 共享订阅运行态。 */
    static final class SharedSubscription {
        final SubscriptionKey key;
        final RosSubscription rosSubscription;
        volatile ScheduledFuture<?> graceTask;

        SharedSubscription(SubscriptionKey key, RosSubscription rosSubscription) {
            this.key = key;
            this.rosSubscription = rosSubscription;
        }
    }
}
