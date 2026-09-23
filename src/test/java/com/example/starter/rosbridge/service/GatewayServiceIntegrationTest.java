package com.example.starter.rosbridge.service;

import com.example.starter.rosbridge.adapter.RosAdapter;
import com.example.starter.rosbridge.adapter.SyntheticRosAdapter;
import com.example.starter.rosbridge.config.RosbridgeProperties;
import com.example.starter.rosbridge.persistence.GatewayRepository;
import com.example.starter.rosbridge.protocol.ClientRequest;
import com.example.starter.rosbridge.testsupport.ControllableScheduledExecutor;
import com.example.starter.rosbridge.testsupport.MutableClock;
import com.example.starter.rosbridge.topic.QosNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GatewayService} 端到端测试：真实 H2（MODE=MySQL）持久层 + 合成 ROS 适配器
 * + 可控时钟/调度器 + 假 WebSocket 会话。覆盖题目要求的主流程与关键边界：
 * 多客户端共享订阅、最后取消才释放上游、慢消费者隔离、Service 超时与迟到结果、
 * Action 取消幂等、ACK 游标与断连重连续投、宽限期到期释放。
 */
class GatewayServiceIntegrationTest {

    private static final String TOPIC = "/chatter";
    private static final String TYPE = SyntheticRosAdapter.TOPIC_TYPE;

    private DriverManagerDataSource dataSource;
    private ObjectMapper mapper;
    private SyntheticRosAdapter adapter;
    private ControllableScheduledExecutor scheduler;
    private MutableClock clock;
    private RosbridgeProperties properties;
    private GatewayService service;
    private final List<FakeConn> conns = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        String dbName = "rb_svc_" + UUID.randomUUID().toString().replace("-", "");
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        ScriptUtils.executeSqlScript(dataSource.getConnection(),
                new org.springframework.core.io.ClassPathResource("schema.sql"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        GatewayRepository repository = new GatewayRepository(jdbc, txManager);

        mapper = new ObjectMapper();
        adapter = new SyntheticRosAdapter(mapper);
        adapter.setServiceLatencyMs(0);
        adapter.setActionStepMs(0);
        scheduler = new ControllableScheduledExecutor();
        clock = new MutableClock();
        properties = new RosbridgeProperties();
        properties.setServiceTimeoutMs(100);
        properties.setReconnectGraceMs(1000);
        properties.setMessageRetentionPerTopic(256);

        service = new GatewayService(adapter, repository, mapper, new QosNormalizer(mapper),
                scheduler, properties, clock);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    void multipleClientsShareOneUpstreamAndLastUnsubscribeReleasesIt() {
        FakeConn c1 = connect("c1", 64);
        FakeConn c2 = connect("c2", 64);
        subscribe(c1, TOPIC);
        subscribe(c2, TOPIC);

        assertEquals(1, service.upstreamCount(TOPIC), "同 topic/类型/QoS 必须只建一个上游订阅");
        assertTrue(c1.frames().stream().anyMatch(f -> f.contains("\"op\":\"SUBSCRIBED\"")));
        assertTrue(c2.frames().stream().anyMatch(f -> f.contains("\"op\":\"SUBSCRIBED\"")));

        // 重复订阅幂等：上游数仍为 1。
        subscribe(c1, TOPIC);
        assertEquals(1, service.upstreamCount(TOPIC));

        emit(TOPIC, "hello");
        Long cursorC1 = c1.lastPublishCursor();
        Long cursorC2 = c2.lastPublishCursor();
        assertEquals(cursorC1, cursorC2, "共享订阅消息使用同一单调游标");
        assertEquals(1L, cursorC1);

        unsubscribe(c1, TOPIC);
        assertEquals(1, service.upstreamCount(TOPIC), "c1 取消后 c2 仍订阅，不得释放上游");

        // c1 重复取消仍收到 UNSUBSCRIBED（幂等），且不影响上游。
        unsubscribe(c1, TOPIC);
        assertEquals(1, service.upstreamCount(TOPIC));

        unsubscribe(c2, TOPIC);
        assertEquals(0, service.upstreamCount(TOPIC), "最后一个客户端取消后才释放上游资源");
    }

    @Test
    void slowConsumerOverflowIsIsolatedFromOtherClients() {
        FakeConn slow = connect("slow", 2);
        FakeConn fast = connect("fast", 64);
        subscribe(slow, TOPIC);
        subscribe(fast, TOPIC);

        slow.paused = true;
        for (int i = 1; i <= 4; i++) {
            emit(TOPIC, "m" + i);
        }
        slow.paused = false;
        scheduler.runPeriodicTasks();

        List<JsonNode> slowGaps = slow.framesJson(mapper).stream()
                .filter(f -> "RESUME_GAP".equals(op(f))).toList();
        assertEquals(1, slowGaps.size(), "慢客户端必须收到 RESUME_GAP");
        assertEquals(1L, slowGaps.get(0).get("fromCursor").asLong());
        assertEquals(2L, slowGaps.get(0).get("toCursor").asLong());
        assertEquals(List.of(3L, 4L), slow.publishCursors(mapper),
                "慢客户端只保留队列上限内最新消息");

        assertEquals(List.of(1L, 2L, 3L, 4L), fast.publishCursors(mapper),
                "其他客户端必须完整收到全部消息");
        assertTrue(fast.frames().stream().noneMatch(t -> t.contains("RESUME_GAP")),
                "其他客户端不得收到 GAP 帧");
    }

    @Test
    void serviceTimeoutReturnsTimeoutAndLateResultDoesNotCompleteAgain() {
        FakeConn c1 = connect("c1", 64);
        AtomicReference<RosAdapter.ServiceCallback> hung = new AtomicReference<>();
        adapter.stubService("/hang", (svc, args, cb, sched, delay) -> hung.set(cb));

        ClientRequest call = new ClientRequest();
        call.setOp("SERVICE_CALL");
        call.setRequestId("req-1");
        call.setService("/hang");
        call.setArgs(mapper.createObjectNode());
        service.handleRequest("c1", call);

        // 未超时前没有终结帧。
        scheduler.tick(50);
        assertTrue(c1.frames().stream().noneMatch(t -> t.contains("SERVICE_ERROR")));

        // 越过 100ms 超时点：必须返回 TIMEOUT。
        scheduler.tick(60);
        List<JsonNode> errors = c1.framesJson(mapper).stream()
                .filter(f -> "SERVICE_ERROR".equals(op(f))).toList();
        assertEquals(1, errors.size());
        assertEquals("TIMEOUT", errors.get(0).get("code").asText());

        // 迟到的成功结果不得再次完成请求、不得下发第二帧。
        hung.get().onSuccess(mapper.createObjectNode().put("late", true));
        scheduler.runPeriodicTasks();
        long errorFrames = c1.frames().stream().filter(t -> t.contains("SERVICE_ERROR")).count();
        long responseFrames = c1.frames().stream().filter(t -> t.contains("SERVICE_RESPONSE")).count();
        assertEquals(1, errorFrames, "迟到结果不得产生重复终结帧");
        assertEquals(0, responseFrames, "超时后迟到成功结果不得下发响应");
    }

    @Test
    void serviceSuccessReturnsResponseAndDuplicateRequestIsIdempotent() {
        FakeConn c1 = connect("c1", 64);
        adapter.stubService("/echo", (svc, args, cb, sched, delay) ->
                cb.onSuccess(mapper.createObjectNode().put("ok", true)));

        ClientRequest call = new ClientRequest();
        call.setOp("SERVICE_CALL");
        call.setRequestId("req-2");
        call.setService("/echo");
        call.setArgs(mapper.createObjectNode().put("n", 7));
        service.handleRequest("c1", call);

        List<JsonNode> responses = c1.framesJson(mapper).stream()
                .filter(f -> "SERVICE_RESPONSE".equals(op(f))).toList();
        assertEquals(1, responses.size());
        assertEquals("req-2", responses.get(0).get("requestId").asText());
        assertTrue(responses.get(0).get("values").get("ok").asBoolean());
    }

    @Test
    void actionCancelAndLateResultAreIdempotent() {
        FakeConn c1 = connect("c1", 64);
        AtomicReference<RosAdapter.GoalListener> listenerRef = new AtomicReference<>();
        AtomicBoolean canceledFlag = new AtomicBoolean();
        adapter.stubAction("/fib", (action, goal, listener, sched, stepMs, canceled) -> {
            listenerRef.set(listener);
            // 转发取消标志，便于断言网关确实调用了 handle.cancel()。
            Thread watcher = new Thread(() -> {
                while (!canceled.get()) {
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                canceledFlag.set(true);
            });
            watcher.setDaemon(true);
            watcher.start();
        });

        ClientRequest send = new ClientRequest();
        send.setOp("ACTION_SEND_GOAL");
        send.setGoalId("goal-1");
        send.setAction("/fib");
        send.setArgs(mapper.createObjectNode().put("n", 10));
        service.handleRequest("c1", send);

        ClientRequest cancel = new ClientRequest();
        cancel.setOp("ACTION_CANCEL");
        cancel.setGoalId("goal-1");
        service.handleRequest("c1", cancel);

        // 重复取消幂等，不产生异常或额外帧。
        service.handleRequest("c1", cancel);

        // 适配器最终给出 CANCELED 结果。
        ObjectNode cancelResult = mapper.createObjectNode();
        listenerRef.get().onResult(new RosAdapter.ActionOutcome("CANCELED", cancelResult));

        List<JsonNode> results = c1.framesJson(mapper).stream()
                .filter(f -> "ACTION_RESULT".equals(op(f))).toList();
        assertEquals(1, results.size(), "Action 只能有一个最终结果帧");
        assertEquals("CANCELED", results.get(0).get("status").asText());
        assertEquals("goal-1", results.get(0).get("goalId").asText());
        assertTrue(canceledFlag.get(), "取消必须透传到适配器 goal 句柄");

        // 迟到的 OK 结果不得再次完成。
        listenerRef.get().onResult(new RosAdapter.ActionOutcome("OK",
                mapper.createObjectNode().put("late", true)));
        assertEquals(1, c1.frames().stream().filter(t -> t.contains("ACTION_RESULT")).count());
    }

    @Test
    void actionFeedbackThenOkResult() {
        FakeConn c1 = connect("c1", 64);
        AtomicReference<RosAdapter.GoalListener> listenerRef = new AtomicReference<>();
        adapter.stubAction("/run", (action, goal, listener, sched, stepMs, canceled) -> {
            listenerRef.set(listener);
            listener.onFeedback(mapper.createObjectNode().put("progress", 50));
        });
        ClientRequest send = new ClientRequest();
        send.setOp("ACTION_SEND_GOAL");
        send.setGoalId("goal-ok");
        send.setAction("/run");
        service.handleRequest("c1", send);

        listenerRef.get().onResult(new RosAdapter.ActionOutcome("OK",
                mapper.createObjectNode().put("done", true)));

        List<JsonNode> feedback = c1.framesJson(mapper).stream()
                .filter(f -> "ACTION_FEEDBACK".equals(op(f))).toList();
        List<JsonNode> results = c1.framesJson(mapper).stream()
                .filter(f -> "ACTION_RESULT".equals(op(f))).toList();
        assertEquals(1, feedback.size());
        assertEquals(50, feedback.get(0).get("feedback").get("progress").asInt());
        assertEquals(1, results.size());
        assertEquals("OK", results.get(0).get("status").asText());
    }

    @Test
    void reconnectResumesAfterAckedCursorWithoutRepeatingConfirmedMessages() {
        FakeConn first = connect("rover", 64);
        subscribe(first, TOPIC);
        emit(TOPIC, "1");
        emit(TOPIC, "2");
        emit(TOPIC, "3");
        assertEquals(List.of(1L, 2L, 3L), first.publishCursors(mapper));

        // ACK 到游标 2。
        ClientRequest ack = new ClientRequest();
        ack.setOp("ACK");
        ack.setCursor(2L);
        service.handleRequest("rover", ack);

        // 断连（宽限期内），离线期间再产生两条消息。
        service.unregisterConnection("rover");
        assertFalse(first.open);
        emit(TOPIC, "4");
        emit(TOPIC, "5");

        // 宽限期内同 clientId 重连：只投递游标 > 2 的可用消息（含未确认的 3），不重复 1、2。
        FakeConn second = connect("rover", 64);
        assertEquals(List.of(3L, 4L, 5L), second.publishCursors(mapper),
                "重连必须从大于确认游标处续投，不得重复已确认消息");
        assertEquals(1, service.upstreamCount(TOPIC), "宽限期内重连上游订阅仍然保留复用");

        // 全部 ACK 后再次重连不应重放任何消息。
        ClientRequest ackAll = new ClientRequest();
        ackAll.setOp("ACK");
        ackAll.setCursor(5L);
        service.handleRequest("rover", ackAll);
        service.unregisterConnection("rover");
        FakeConn third = connect("rover", 64);
        assertEquals(List.of(), third.publishCursors(mapper), "已全部确认后重连不得重放");
    }

    @Test
    void upstreamReleasedAfterReconnectGraceExpires() {
        FakeConn c1 = connect("grace", 64);
        subscribe(c1, TOPIC);
        assertEquals(1, service.upstreamCount(TOPIC));

        service.unregisterConnection("grace");
        // 宽限期未到，上游仍保留，供重连续投。
        scheduler.tick(500);
        assertEquals(1, service.upstreamCount(TOPIC));

        // 宽限期结束，释放上游资源。
        scheduler.tick(600);
        assertEquals(0, service.upstreamCount(TOPIC));
    }

    @Test
    void qosKeyOrderDifferenceStillSharesUpstream() {
        FakeConn c1 = connect("qa", 64);
        FakeConn c2 = connect("qb", 64);
        ClientRequest s1 = new ClientRequest();
        s1.setOp("SUBSCRIBE");
        s1.setTopic("/qos");
        s1.setType(TYPE);
        s1.setQos(mapper.createObjectNode().put("depth", 10).put("reliability", "reliable"));
        ClientRequest s2 = new ClientRequest();
        s2.setOp("SUBSCRIBE");
        s2.setTopic("/qos");
        s2.setType(TYPE);
        s2.setQos(mapper.createObjectNode().put("reliability", "reliable").put("depth", 10));
        service.handleRequest("qa", s1);
        service.handleRequest("qb", s2);
        assertEquals(1, service.upstreamCount("/qos"),
                "键顺序不同但语义相同的 QoS 必须复用同一上游");
    }

    @Test
    void rateLimitedClientReceivesAtMostOneDataFramePerSecond() {
        FakeConn slow = connect("rate", 64, 1);
        subscribe(slow, TOPIC);
        for (int i = 1; i <= 5; i++) {
            emit(TOPIC, "r" + i);
        }
        // 初始令牌桶有 1 个令牌：仅游标 1 发出，其余排队（桶容量=速率，不累积突发）。
        assertEquals(List.of(1L), slow.publishCursors(mapper));

        // 每推进 1 秒补充 1 个令牌，按节奏再发 2 条。
        clock.advance(1001);
        scheduler.runPeriodicTasks();
        assertEquals(List.of(1L, 2L), slow.publishCursors(mapper));
        clock.advance(1001);
        scheduler.runPeriodicTasks();
        assertEquals(List.of(1L, 2L, 3L), slow.publishCursors(mapper),
                "限速客户端必须按令牌桶节奏收消息");
    }

    @Test
    void duplicatePendingServiceCallDoesNotCreateSecondInvocation() {
        FakeConn c1 = connect("c1", 64);
        AtomicInteger invocations = new AtomicInteger();
        AtomicReference<RosAdapter.ServiceCallback> cb = new AtomicReference<>();
        adapter.stubService("/once", (svc, args, callback, sched, delay) -> {
            invocations.incrementAndGet();
            cb.set(callback);
        });

        ClientRequest call = new ClientRequest();
        call.setOp("SERVICE_CALL");
        call.setRequestId("req-dup");
        call.setService("/once");
        call.setArgs(mapper.createObjectNode());
        service.handleRequest("c1", call);
        service.handleRequest("c1", call); // PENDING 期间同 requestId 重发
        assertEquals(1, invocations.get(), "同一 requestId 在途时不得再次调用适配器");
        assertEquals(0, c1.frames().stream().filter(t -> t.contains("SERVICE_")).count(),
                "PENDING 重发不得下发任何终结帧");

        cb.get().onSuccess(mapper.createObjectNode().put("ok", true));
        long responses = c1.frames().stream().filter(t -> t.contains("SERVICE_RESPONSE")).count();
        assertEquals(1, responses, "完成后只有一个响应帧");

        // 终态后同 requestId 再发：重放既有最终帧，不二次调用。
        service.handleRequest("c1", call);
        assertEquals(1, invocations.get());
        assertEquals(2, c1.frames().stream().filter(t -> t.contains("SERVICE_RESPONSE")).count(),
                "终态后重发仅重放最终结果");
    }

    // ---- 辅助 ----

    private FakeConn connect(String clientId, int queueCapacity) {
        return connect(clientId, queueCapacity, 0);
    }

    private FakeConn connect(String clientId, int queueCapacity, int rateLimitPerSecond) {
        FakeConn conn = new FakeConn(clientId);
        WebSocketSession ws = conn.session();
        service.registerConnection(ws, clientId, rateLimitPerSecond, queueCapacity);
        conns.add(conn);
        return conn;
    }

    private void subscribe(FakeConn conn, String topic) {
        ClientRequest req = new ClientRequest();
        req.setOp("SUBSCRIBE");
        req.setTopic(topic);
        req.setType(TYPE);
        service.handleRequest(conn.clientId, req);
    }

    private void unsubscribe(FakeConn conn, String topic) {
        ClientRequest req = new ClientRequest();
        req.setOp("UNSUBSCRIBE");
        req.setTopic(topic);
        service.handleRequest(conn.clientId, req);
    }

    private void emit(String topic, String data) {
        ObjectNode node = mapper.createObjectNode();
        node.put("data", data);
        int delivered = adapter.emit(topic, node);
        assertTrue(delivered >= 1, "合成消息至少应进入一个上游订阅");
    }

    private static String op(JsonNode frame) {
        return frame.get("op").asText();
    }

    /** 假 WebSocket 连接：捕获下发帧，可暂停写入模拟慢消费者。 */
    private static final class FakeConn {
        private final String clientId;
        private final List<String> frames = new CopyOnWriteArrayList<>();
        private volatile boolean paused;
        private volatile boolean open = true;

        private FakeConn(String clientId) {
            this.clientId = clientId;
        }

        private WebSocketSession session() {
            return (WebSocketSession) Proxy.newProxyInstance(
                    WebSocketSession.class.getClassLoader(),
                    new Class<?>[]{WebSocketSession.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "sendMessage":
                                if (paused) {
                                    throw new java.io.IOException("模拟底层不可写");
                                }
                                frames.add(((TextMessage) args[0]).getPayload());
                                return null;
                            case "isOpen":
                                return open;
                            case "close":
                                open = false;
                                return null;
                            case "getAttributes":
                                return new java.util.HashMap<String, Object>();
                            case "getId":
                                return clientId;
                            default:
                                Class<?> rt = method.getReturnType();
                                if (rt == boolean.class) {
                                    return false;
                                }
                                if (rt == int.class) {
                                    return 0;
                                }
                                if (rt == long.class) {
                                    return 0L;
                                }
                                return null;
                        }
                    });
        }

        private List<String> frames() {
            return frames;
        }

        private List<JsonNode> framesJson(ObjectMapper mapper) {
            List<JsonNode> nodes = new ArrayList<>();
            for (String text : frames) {
                try {
                    nodes.add(mapper.readTree(text));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return nodes;
        }

        private List<Long> publishCursors(ObjectMapper mapper) {
            return framesJson(mapper).stream()
                    .filter(f -> "PUBLISH".equals(op(f)))
                    .map(f -> f.get("cursor").asLong()).toList();
        }

        private Long lastPublishCursor() {
            List<Long> cursors = framesJson(new ObjectMapper()).stream()
                    .filter(f -> "PUBLISH".equals(op(f)))
                    .map(f -> f.get("cursor").asLong()).toList();
            return cursors.isEmpty() ? null : cursors.get(cursors.size() - 1);
        }
    }
}
