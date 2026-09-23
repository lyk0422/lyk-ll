package com.example.starter.rosbridge.gateway;

import com.example.starter.rosbridge.protocol.Protocol;
import com.example.starter.rosbridge.ros.GatewayClock;
import com.example.starter.rosbridge.ros.GoalHandle;
import com.example.starter.rosbridge.ros.Qos;
import com.example.starter.rosbridge.ros.RosAdapter;
import com.example.starter.rosbridge.store.ActionGoalRow;
import com.example.starter.rosbridge.store.ClientSubscriptionRow;
import com.example.starter.rosbridge.store.GatewayRepository;
import com.example.starter.rosbridge.store.ServiceCallRow;
import com.example.starter.rosbridge.store.SharedSubscriptionRow;
import com.example.starter.rosbridge.store.TopicMessageRow;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 网关核心：客户端会话、共享上游订阅引用计数、单调游标投递与 ACK 续传、
 * Service 超时与 Action 取消的幂等状态机。
 *
 * <p>状态以 H2 为准（见 {@link GatewayRepository}），内存中仅保存活跃连接会话、
 * 上游订阅句柄与 goal 句柄；维护任务周期性处理 Service 超时与上游宽限释放，
 * 既按真实时间调度，也可通过 {@link #runMaintenance()} 在测试虚拟时钟下显式触发。</p>
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    /** goal 句柄映射键中 clientId 与 goalId 之间的分隔符（SOH 控制字符，避免与业务标识冲突）。 */
    private static final String KEY_SEP = Character.toString((char) 1);

    private final GatewayRepository repo;
    private final RosAdapter rosAdapter;
    private final GatewayClock clock;
    private final GatewayProperties props;

    /** clientId -&gt; 活跃连接会话；同一 clientId 只允许一个活跃连接。 */
    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();

    /** sharedId -&gt; 共享订阅运行时（上游句柄、当前在线成员）。 */
    private final Map<Long, SharedRuntime> runtimes = new ConcurrentHashMap<>();

    /** (clientId 分隔 goalId) -&gt; 适配器 goal 句柄。 */
    private final Map<String, GoalHandle> goalHandles = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;

    public GatewayService(GatewayRepository repo, RosAdapter rosAdapter,
                          GatewayClock clock, GatewayProperties props) {
        this.repo = repo;
        this.rosAdapter = rosAdapter;
        this.clock = clock;
        this.props = props;
    }

    /** 由配置在就绪后启动周期维护（100ms 粒度）。 */
    public void startMaintenance() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rosbridge-maintenance");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::safeMaintenance,
                100, 100, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        for (Long sharedId : new ArrayList<>(runtimes.keySet())) {
            releaseUpstreamNow(sharedId);
        }
    }

    private void safeMaintenance() {
        try {
            runMaintenance();
        } catch (Throwable t) {
            log.warn("维护任务异常", t);
        }
    }

    /**
     * 执行一轮维护：到期 Service 调用置 TIMEOUT；过宽限期且无引用的共享订阅释放上游；
     * 驱动各客户端限速发送。可在测试中直接调用（配合虚拟时钟）。
     */
    public void runMaintenance() {
        long now = clock.nowMillis();
        for (ServiceCallRow call : repo.findExpiredPendingServiceCalls(now)) {
            if (repo.completeServiceCall(call.clientId(), call.requestId(),
                    ServiceCallRow.TIMEOUT, null, now)) {
                sendText(call.clientId(), Protocol.serviceResponse(
                        call.requestId(), call.serviceName(),
                        Protocol.SERVICE_TIMEOUT, null, "service call timed out"));
                log.debug("Service 调用 {}#{} 超时", call.clientId(), call.requestId());
            }
        }
        for (SharedSubscriptionRow row : repo.listSharedSubs()) {
            if (row.refCount() == 0 && row.graceUntil() != null && row.graceUntil() <= now) {
                if (repo.tryReleaseUpstreamAfterGrace(row.id(), now)) {
                    repo.deleteGracingSubsForShared(row.id());
                    releaseUpstreamNow(row.id());
                }
            }
        }
        for (ClientSession session : sessions.values()) {
            session.pump();
        }
    }

    // ---------------- 连接生命周期 ----------------

    /**
     * 建立/恢复连接。同 clientId 已有旧连接时关闭旧连接（单活）；
     * 恢复宽限中的订阅并从上次 ACK 游标之后续传。
     */
    public ClientSession connect(ClientTransport transport, Integer ratePerSec, Integer queueCap) {
        String clientId = transport.clientId();
        int rate = ratePerSec != null && ratePerSec > 0 ? ratePerSec : props.defaultRatePerSec();
        int cap = queueCap != null && queueCap > 0 ? queueCap : props.defaultQueueCap();

        ClientSession old = sessions.remove(clientId);
        if (old != null) {
            old.transport().close();
        }
        long now = clock.nowMillis();
        repo.upsertConnectedClient(clientId, rate, cap, now);

        ClientSession session = new ClientSession(clientId, transport, rate, cap);
        sessions.put(clientId, session);
        session.enqueueText(Protocol.connected());

        resumeAfterReconnect(clientId, session);
        session.pump();
        return session;
    }

    /**
     * 断连：会话移除，全部 ACTIVE 订阅释放引用并进入宽限；幂等，重复断连无副作用。
     * 仅当传入的就是当前会话时才处理，避免旧连接关闭事件误伤新连接。
     */
    public void disconnect(ClientTransport transport) {
        String clientId = transport.clientId();
        ClientSession session = sessions.get(clientId);
        if (session == null || session.transport() != transport) {
            return;
        }
        sessions.remove(clientId);
        long now = clock.nowMillis();
        long graceUntil = now + props.graceMs();
        repo.markDisconnected(clientId, now);
        for (ClientSubscriptionRow cs : repo.listClientSubs(clientId)) {
            if (ClientSubscriptionRow.ACTIVE.equals(cs.status())) {
                SharedSubscriptionRow shared = repo.releaseReference(cs.sharedId(), graceUntil);
                detachClient(cs.sharedId(), clientId);
                if (shared != null && shared.refCount() == 0) {
                    scheduleGraceCheck(cs.sharedId());
                }
            }
        }
        repo.markClientSubsGracing(clientId, graceUntil);
        log.debug("客户端 {} 断连，订阅进入 {}ms 宽限", clientId, props.graceMs());
    }

    // ---------------- Topic 订阅/取消/ACK/投递 ----------------

    /**
     * 订阅 Topic。Topic+类型+QoS 相同则复用同一上游订阅；重复订阅幂等；
     * 宽限内重订阅恢复引用，不重建上游。
     */
    public void subscribe(String clientId, String topic, String type, Qos qos) {
        long now = clock.nowMillis();
        String qosKey = qos.key();
        SharedSubscriptionRow shared = repo.findShared(topic, type, qosKey);
        if (shared == null) {
            try {
                shared = repo.insertShared(topic, type, qosKey, now);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                shared = repo.findShared(topic, type, qosKey);
            }
        }
        long sharedId = shared.id();

        ClientSubscriptionRow cs = repo.findClientSub(clientId, sharedId);
        boolean needReplay = false;
        if (cs == null) {
            repo.insertClientSub(clientId, sharedId, now);
            shared = repo.acquireReference(sharedId);
            ensureUpstream(sharedId, topic, type, qos);
            attachClient(sharedId, clientId);
            needReplay = true;
        } else if (ClientSubscriptionRow.GRACING.equals(cs.status())) {
            repo.reactivateClientSub(clientId, sharedId);
            shared = repo.acquireReference(sharedId);
            ensureUpstream(sharedId, topic, type, qos);
            attachClient(sharedId, clientId);
            needReplay = true;
        }
        // ACTIVE 的重复订阅：幂等，只回 subscribed

        sendText(clientId, Protocol.subscribed(topic, type, qosKey));
        if (needReplay) {
            replay(clientId, sharedId, topic, type, qosKey);
        }
        ClientSession session = sessions.get(clientId);
        if (session != null) {
            session.pump();
        }
    }

    /**
     * 取消订阅。重复取消/取消不存在的订阅均幂等：只有真实存在且 ACTIVE 的关系才释放引用。
     */
    public void unsubscribe(String clientId, String topic, String type, Qos qos) {
        String qosKey = qos.key();
        SharedSubscriptionRow shared = repo.findShared(topic, type, qosKey);
        if (shared != null) {
            ClientSubscriptionRow cs = repo.findClientSub(clientId, shared.id());
            if (cs != null) {
                repo.deleteClientSub(clientId, shared.id());
                if (ClientSubscriptionRow.ACTIVE.equals(cs.status())) {
                    detachClient(shared.id(), clientId);
                    shared = repo.releaseReference(shared.id(),
                            clock.nowMillis() + props.graceMs());
                    if (shared.refCount() == 0) {
                        scheduleGraceCheck(shared.id());
                    }
                }
            }
        }
        sendText(clientId, Protocol.unsubscribed(topic, type, qosKey));
    }

    /**
     * 保存客户端确认游标；游标必须单调递增，回退/重复 ACK 不改变已确认位置。
     */
    public void ack(String clientId, String topic, String type, Qos qos, long cursor) {
        SharedSubscriptionRow shared = repo.findShared(topic, type, qos.key());
        if (shared == null) {
            return;
        }
        repo.updateAck(clientId, shared.id(), cursor);
    }

    /**
     * 上游消息到达：分配共享单调游标、留存、向在线成员扇出。
     */
    public void onUpstreamMessage(long sharedId, JsonNode payload) {
        SharedSubscriptionRow shared = repo.findSharedById(sharedId);
        if (shared == null) {
            return;
        }
        long now = clock.nowMillis();
        long cursor = repo.allocateCursor(sharedId);
        String payloadJson = Protocol.toJson(payload);
        repo.insertMessage(sharedId, cursor, payloadJson, now);
        repo.pruneMessages(sharedId, props.retention());

        String frame = Protocol.topicMessage(
                shared.topic(), shared.msgType(), shared.qosKey(), cursor, payload);
        SharedRuntime runtime = runtimes.get(sharedId);
        if (runtime != null) {
            for (String memberClientId : runtime.onlineClients()) {
                ClientSession session = sessions.get(memberClientId);
                if (session != null) {
                    session.enqueueTopic(sharedId, cursor, frame,
                            shared.topic(), shared.msgType(), shared.qosKey());
                    session.pump();
                }
            }
        }
    }

    // ---------------- Service ----------------

    /**
     * 发起 Service 调用。同一 (clientId,requestId) 重复请求幂等，只调用一次适配器；
     * 超时后适配器迟到结果因 CAS 失败被忽略，不会再次完成请求。
     */
    public void callService(String clientId, String requestId, String service,
                            String type, JsonNode args, Long timeoutMs) {
        long now = clock.nowMillis();
        long timeout = timeoutMs != null && timeoutMs > 0 ? timeoutMs : props.defaultTimeoutMs();
        // 同一 (clientId,requestId) 已存在（进行中或已完成）即幂等，不重复调用适配器
        if (repo.getServiceCall(clientId, requestId) != null) {
            return;
        }
        repo.insertServiceCallIfAbsent(clientId, requestId, service, now + timeout, now);
        try {
            rosAdapter.callService(service, type, args, outcome -> {
                long at = clock.nowMillis();
                String status = outcome.ok() ? ServiceCallRow.OK : ServiceCallRow.ERROR;
                String resultJson = outcome.ok() && outcome.value() != null
                        ? Protocol.toJson(outcome.value()) : null;
                if (repo.completeServiceCall(clientId, requestId, status, resultJson, at)) {
                    sendText(clientId, Protocol.serviceResponse(
                            requestId, service,
                            outcome.ok() ? Protocol.SERVICE_OK : Protocol.SERVICE_ERROR,
                            outcome.ok() ? outcome.value() : null,
                            outcome.ok() ? null : outcome.error()));
                }
                // CAS 失败说明已 TIMEOUT 或已完成：迟到结果被丢弃
            });
        } catch (RuntimeException e) {
            if (repo.completeServiceCall(clientId, requestId, ServiceCallRow.ERROR, null, now)) {
                sendText(clientId, Protocol.serviceResponse(
                        requestId, service, Protocol.SERVICE_ERROR, null, e.getMessage()));
            }
        }
    }

    // ---------------- Action ----------------

    /**
     * 提交 Action goal。重复 goalId 不重复提交；feedback 仅在 goal 仍 ACTIVE 时投递；
     * 最终结果与取消通过 CAS 保证只有一个终态生效。
     */
    public void sendGoal(String clientId, String goalId, String action,
                         String type, JsonNode goal) {
        long now = clock.nowMillis();
        // 同一 (clientId,goalId) 已存在（进行中或已终态）即幂等，不重复提交适配器
        if (repo.getGoal(clientId, goalId) != null) {
            return;
        }
        repo.insertGoalIfAbsent(clientId, goalId, action, now);
        GoalHandle handle = rosAdapter.sendGoal(action, type, goal,
                feedback -> {
                    ActionGoalRow current = repo.getGoal(clientId, goalId);
                    if (current != null && ActionGoalRow.ACTIVE.equals(current.status())) {
                        sendText(clientId, Protocol.actionFeedback(goalId, action, feedback));
                    }
                },
                outcome -> {
                    String resultJson = outcome.result() != null
                            ? Protocol.toJson(outcome.result()) : null;
                    if (repo.casFinishGoal(clientId, goalId, outcome.status(), resultJson,
                            clock.nowMillis())) {
                        goalHandles.remove(goalKey(clientId, goalId));
                        deliverGoalResult(repo.getGoal(clientId, goalId));
                    }
                });
        goalHandles.put(goalKey(clientId, goalId), handle);
    }

    /**
     * 取消 goal。ACTIVE 时 CAS 置 CANCELED 并通知适配器取消；已终态（含已取消）时
     * 幂等返回当前结果，不产生第二个终态。
     */
    public void cancelGoal(String clientId, String goalId) {
        ActionGoalRow current = repo.getGoal(clientId, goalId);
        if (current == null) {
            sendText(clientId,
                    Protocol.error(Protocol.OP_CANCEL_GOAL, "unknown goalId: " + goalId));
            return;
        }
        if (ActionGoalRow.ACTIVE.equals(current.status())) {
            if (repo.casFinishGoal(clientId, goalId, ActionGoalRow.CANCELED, null,
                    clock.nowMillis())) {
                GoalHandle handle = goalHandles.remove(goalKey(clientId, goalId));
                if (handle != null) {
                    try {
                        handle.cancel();
                    } catch (RuntimeException e) {
                        log.warn("适配器取消 goal {} 失败", goalId, e);
                    }
                }
            }
        }
        deliverGoalResult(repo.getGoal(clientId, goalId));
    }

    private void deliverGoalResult(ActionGoalRow row) {
        if (row == null) {
            return;
        }
        JsonNode result = null;
        if (row.resultJson() != null) {
            try {
                result = Protocol.MAPPER.readTree(row.resultJson());
            } catch (Exception e) {
                result = null;
            }
        }
        sendText(row.clientId(), Protocol.actionResult(
                row.goalId(), row.actionName(), row.status(), result));
    }

    // ---------------- 内部：上游生命周期与续传 ----------------

    private void resumeAfterReconnect(String clientId, ClientSession session) {
        for (ClientSubscriptionRow cs : repo.listClientSubs(clientId)) {
            if (!ClientSubscriptionRow.GRACING.equals(cs.status())) {
                continue;
            }
            SharedSubscriptionRow shared = repo.findSharedById(cs.sharedId());
            if (shared == null) {
                continue;
            }
            repo.reactivateClientSub(clientId, shared.id());
            shared = repo.acquireReference(shared.id());
            ensureUpstream(shared.id(), shared.topic(), shared.msgType(),
                    qosFromKey(shared.qosKey()));
            attachClient(shared.id(), clientId);
            replay(clientId, shared.id(), shared.topic(), shared.msgType(), shared.qosKey());
        }
    }

    private static Qos qosFromKey(String qosKey) {
        String[] parts = qosKey.split("\\|", 3);
        if (parts.length == 3) {
            try {
                return new Qos(parts[0], parts[1], Integer.parseInt(parts[2]));
            } catch (NumberFormatException ignored) {
                // 落到默认值
            }
        }
        return Qos.defaults();
    }

    /** 把游标严格大于已确认位置的留存消息补投给客户端，已确认消息不重复。 */
    private void replay(String clientId, long sharedId, String topic, String type, String qosKey) {
        ClientSubscriptionRow cs = repo.findClientSub(clientId, sharedId);
        ClientSession session = sessions.get(clientId);
        if (cs == null || session == null) {
            return;
        }
        List<TopicMessageRow> backlog = repo.messagesAfter(sharedId, cs.lastAckCursor());
        if (backlog.isEmpty()) {
            return;
        }
        List<OutboundEntry.Topic> entries = new ArrayList<>(backlog.size());
        for (TopicMessageRow msg : backlog) {
            JsonNode payload;
            try {
                payload = Protocol.MAPPER.readTree(msg.payload());
            } catch (Exception e) {
                continue;
            }
            String frame = Protocol.topicMessage(topic, type, qosKey, msg.cursor(), payload);
            entries.add(new OutboundEntry.Topic(
                    sharedId, msg.cursor(), topic, type, qosKey, frame));
        }
        session.enqueueResumeBatch(entries);
    }

    /**
     * 确保上游订阅存在：DB CAS（tryMarkUpstreamActive）保证并发下只有一个创建者
     * 真正向 ROS 建立订阅，其余复用已建运行时。
     */
    private void ensureUpstream(long sharedId, String topic, String type, Qos qos) {
        SharedRuntime existing = runtimes.get(sharedId);
        if (existing != null) {
            return;
        }
        synchronized (runtimes) {
            if (runtimes.containsKey(sharedId)) {
                return;
            }
            if (!repo.tryMarkUpstreamActive(sharedId)) {
                return;
            }
            AutoCloseable subscription = rosAdapter.subscribe(
                    topic, type, qos, payload -> onUpstreamMessage(sharedId, payload));
            log.debug("建立上游订阅 {} [{} {}]", topic, type, qos.key());
            runtimes.put(sharedId, new SharedRuntime(topic, type, qos.key(), subscription));
        }
    }

    private void releaseUpstreamNow(long sharedId) {
        SharedRuntime runtime = runtimes.remove(sharedId);
        if (runtime != null) {
            try {
                runtime.upstream().close();
            } catch (Exception e) {
                log.warn("关闭上游订阅 {} 失败", runtime.topic(), e);
            }
            repo.deleteMessages(sharedId);
            log.debug("已释放上游订阅 {}", runtime.topic());
        }
    }

    private void attachClient(long sharedId, String clientId) {
        SharedRuntime runtime = runtimes.get(sharedId);
        if (runtime != null) {
            runtime.attach(clientId);
        }
    }

    private void detachClient(long sharedId, String clientId) {
        SharedRuntime runtime = runtimes.get(sharedId);
        if (runtime != null) {
            runtime.detach(clientId);
        }
    }

    private void scheduleGraceCheck(long sharedId) {
        if (scheduler == null) {
            return;
        }
        try {
            scheduler.schedule(this::safeMaintenance,
                    props.graceMs() + 50, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            // 关闭过程中调度可能失败，周期维护仍会兜底
        }
    }

    private void sendText(String clientId, String frame) {
        ClientSession session = sessions.get(clientId);
        if (session != null) {
            session.enqueueText(frame);
            session.pump();
        }
    }

    private static String goalKey(String clientId, String goalId) {
        return clientId + KEY_SEP + goalId;
    }

    int activeSessionCount() {
        return sessions.size();
    }

    ClientSession session(String clientId) {
        return sessions.get(clientId);
    }

    boolean upstreamExists(long sharedId) {
        return runtimes.containsKey(sharedId);
    }
}
