package com.example.starter.rosbridge.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.starter.rosbridge.protocol.ProtocolJson;
import com.example.starter.rosbridge.ros.SubscriptionKey;
import com.example.starter.rosbridge.ros.SyntheticRosAdapter;
import com.example.starter.rosbridge.store.GatewayRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 网关核心协议与状态测试（H2 + 合成 ROS 适配器 + 录制会话，无真实 WebSocket/ROS）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(RecordingSessionFactory.Config.class)
class GatewayCoreTest {

    @Autowired
    private GatewayCore gateway;
    @Autowired
    private RecordingSessionFactory sessions;
    @Autowired
    private SyntheticRosAdapter ros;
    @Autowired
    private GatewayRepository repository;
    @Autowired
    private BoundedMessageLedger ledger;

    @BeforeEach
    void clean() {
        gateway.releaseAllForTesting();
        repository.deleteAll();
        ledger.clear();
    }

    private String connect(String clientId) {
        String sid = GatewayCore.newSessionId();
        gateway.connect(clientId, sid, null, null, null);
        return sid;
    }

    private void publish(String topic, String type, String qosJson, long seq) {
        JsonNode msg = ProtocolJson.MAPPER.createObjectNode().put("seq", seq).put("data", "m" + seq);
        assertThat(ros.publish(new SubscriptionKey(topic, type, qosJson), msg)).isTrue();
    }

    private String subscribe(String clientId, String topic, String type, String qosJson) {
        return "{\"op\":\"SUBSCRIBE\",\"topic\":\"" + topic + "\",\"type\":\"" + type + "\",\"qos\":"
                + (qosJson == null ? "{}" : qosJson) + "}";
    }

    private void pollAssert(java.util.function.BooleanSupplier cond, long timeoutMs, String desc)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("condition not met in time: " + desc + " (upstream count="
                + ros.activeSubscriptionCount() + ")");
    }

    @Test
    void multipleClientsShareOneUpstreamAndReceiveSameMonotonicCursor() throws Exception {
        connect("c1");
        connect("c2");
        gateway.handleText("c1", subscribe("c1", "/shared", "std_msgs/String",
                "{\"reliability\":\"reliable\",\"depth\":10}"));
        // 字段顺序不同但语义相同的 QoS 必须复用同一上游
        gateway.handleText("c2", subscribe("c2", "/shared", "std_msgs/String",
                "{\"depth\":10,\"reliability\":\"reliable\"}"));
        // 与首次完全等价的重复订阅幂等，引用计数不增加
        gateway.handleText("c1", subscribe("c1", "/shared", "std_msgs/String",
                "{\"depth\":10,\"reliability\":\"reliable\"}"));

        assertThat(sessions.awaitFrame("c1", "CONNACK", 1000)).isNotNull();
        assertThat(sessions.awaitFrame("c1", "SUBSCRIBED", 1000)).isNotNull();
        assertThat(sessions.awaitFrame("c2", "CONNACK", 1000)).isNotNull();
        assertThat(sessions.awaitFrame("c2", "SUBSCRIBED", 1000)).isNotNull();
        // 重复订阅的第二个 SUBSCRIBED
        assertThat(sessions.awaitFrame("c1", "SUBSCRIBED", 1000)).isNotNull();

        assertThat(ros.activeSubscriptionCount()).isEqualTo(1);
        SubscriptionKey key = new SubscriptionKey("/shared", "std_msgs/String",
                "{\"depth\":10,\"reliability\":\"reliable\"}");
        assertThat(ros.publish(key, ProtocolJson.MAPPER.createObjectNode().put("seq", 1))).isTrue();

        JsonNode f1 = sessions.awaitFrame("c1", "TOPIC", 1000);
        JsonNode f2 = sessions.awaitFrame("c2", "TOPIC", 1000);
        assertThat(f1.path("cursor").asLong()).isEqualTo(1);
        assertThat(f2.path("cursor").asLong()).isEqualTo(1);
        assertThat(f1.path("data").path("seq").asLong()).isEqualTo(1);
        assertThat(repository.getRefcount(key)).isEqualTo(2);
    }

    @Test
    void upstreamReleasedOnlyAfterLastCancelAndGracePeriod() throws Exception {
        connect("lc1");
        connect("lc2");
        gateway.handleText("lc1", subscribe("lc1", "/last", "std_msgs/String", null));
        gateway.handleText("lc2", subscribe("lc2", "/last", "std_msgs/String", null));
        SubscriptionKey key = new SubscriptionKey("/last", "std_msgs/String", "{}");
        assertThat(ros.activeSubscriptionCount()).isEqualTo(1);

        gateway.handleText("lc1", "{\"op\":\"UNSUBSCRIBE\",\"topic\":\"/last\"}");
        gateway.handleText("lc1", "{\"op\":\"UNSUBSCRIBE\",\"topic\":\"/last\"}"); // 重复取消幂等
        assertThat(sessions.awaitFrame("lc1", "UNSUBSCRIBED", 1000)).isNotNull();
        assertThat(sessions.awaitFrame("lc1", "UNSUBSCRIBED", 1000)).isNotNull();
        // 还有一个订阅者，宽限期内不得释放
        Thread.sleep(300);
        assertThat(ros.activeSubscriptionCount()).isEqualTo(1);
        assertThat(repository.getRefcount(key)).isEqualTo(1);

        gateway.handleText("lc2", "{\"op\":\"UNSUBSCRIBE\",\"topic\":\"/last\"}");
        // 宽限期（测试配置 200ms）结束后才真正释放
        assertThat(ros.activeSubscriptionCount()).isEqualTo(1);
        pollAssert(() -> ros.activeSubscriptionCount() == 0, 2000, "upstream released after grace");
        assertThat(repository.getRefcount(key)).isZero();
    }

    @Test
    void resubscribeWithinGraceReusesUpstream() throws Exception {
        connect("g1");
        gateway.handleText("g1", subscribe("g1", "/grace", "std_msgs/String", null));
        assertThat(ros.activeSubscriptionCount()).isEqualTo(1);
        gateway.handleText("g1", "{\"op\":\"UNSUBSCRIBE\",\"topic\":\"/grace\"}");
        Thread.sleep(50); // 仍在 200ms 宽限期内
        gateway.handleText("g1", subscribe("g1", "/grace", "std_msgs/String", null));
        Thread.sleep(400); // 越过原本的宽限时刻
        assertThat(ros.activeSubscriptionCount()).isEqualTo(1); // 复用，未释放
        publish("/grace", "std_msgs/String", "{}", 9);
        JsonNode f = sessions.awaitFrame("g1", "TOPIC", 1000);
        assertThat(f.path("data").path("seq").asLong()).isEqualTo(9);
    }

    @Test
    void serviceTimeoutReturnsTimeoutAndLateCompletionIgnored() throws Exception {
        connect("svc");
        gateway.handleText("svc", "{\"op\":\"CALL_SERVICE\",\"requestId\":\"r1\",\"service\":\"/slow_service\"}");
        gateway.handleText("svc", "{\"op\":\"CALL_SERVICE\",\"requestId\":\"r1\",\"service\":\"/slow_service\"}");
        assertThat(sessions.awaitFrame("svc", "CONNACK", 1000)).isNotNull();
        JsonNode resp = sessions.awaitFrame("svc", "SERVICE_RESPONSE", 2000);
        assertThat(resp).isNotNull();
        assertThat(resp.path("status").asText()).isEqualTo("TIMEOUT");
        assertThat(repository.getCallStatus("svc", "r1")).isEqualTo("TIMEOUT");
        // 不应有第二个响应（重复请求幂等 + 迟到结果不再完成）
        List<JsonNode> rest = sessions.drain("svc");
        assertThat(rest).noneMatch(n -> "SERVICE_RESPONSE".equals(n.path("op").asText()));
    }

    @Test
    void serviceSuccessCompletesOnce() throws Exception {
        connect("svc2");
        gateway.handleText("svc2",
                "{\"op\":\"CALL_SERVICE\",\"requestId\":\"r2\",\"service\":\"/echo\",\"args\":{\"x\":1}}");
        assertThat(sessions.awaitFrame("svc2", "CONNACK", 1000)).isNotNull();
        JsonNode resp = sessions.awaitFrame("svc2", "SERVICE_RESPONSE", 2000);
        assertThat(resp.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(resp.path("result").path("echo").path("x").asInt()).isEqualTo(1);
        assertThat(repository.getCallStatus("svc2", "r2")).isEqualTo("COMPLETED");
    }

    @Test
    void actionCancelIsIdempotentWithFinalResult() throws Exception {
        connect("act");
        gateway.handleText("act", "{\"op\":\"SEND_GOAL\",\"goalId\":\"g1\",\"action\":\"/slow_action\"}");
        assertThat(sessions.awaitFrame("act", "CONNACK", 1000)).isNotNull();
        assertThat(sessions.awaitFrame("act", "GOAL_ACCEPTED", 1000)).isNotNull();
        assertThat(sessions.awaitFrame("act", "ACTION_FEEDBACK", 1000)).isNotNull();

        gateway.handleText("act", "{\"op\":\"CANCEL_GOAL\",\"goalId\":\"g1\",\"action\":\"/slow_action\"}");
        gateway.handleText("act", "{\"op\":\"CANCEL_GOAL\",\"goalId\":\"g1\",\"action\":\"/slow_action\"}"); // 幂等
        JsonNode result = sessions.awaitFrame("act", "ACTION_RESULT", 1000);
        assertThat(result.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(repository.getGoalStatus("act", "g1")).isEqualTo("CANCELLED");

        // 等待一段时间，确认没有第二个终态帧
        Thread.sleep(200);
        List<JsonNode> rest = sessions.drain("act");
        assertThat(rest).noneMatch(n -> "ACTION_RESULT".equals(n.path("op").asText()));
        assertThat(rest).noneMatch(n -> "GOAL_CANCELLED".equals(n.path("op").asText()));
    }

    @Test
    void actionSuccessThenCancelHasNoEffect() throws Exception {
        connect("act2");
        gateway.handleText("act2", "{\"op\":\"SEND_GOAL\",\"goalId\":\"g2\",\"action\":\"/do_work\"}");
        gateway.handleText("act2", "{\"op\":\"SEND_GOAL\",\"goalId\":\"g2\",\"action\":\"/do_work\"}"); // 幂等
        assertThat(sessions.awaitFrame("act2", "CONNACK", 1000)).isNotNull();
        assertThat(sessions.awaitFrame("act2", "GOAL_ACCEPTED", 1000)).isNotNull();
        assertThat(sessions.awaitFrame("act2", "ACTION_FEEDBACK", 1000)).isNotNull();
        JsonNode result = sessions.awaitFrame("act2", "ACTION_RESULT", 1000);
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
        gateway.handleText("act2", "{\"op\":\"CANCEL_GOAL\",\"goalId\":\"g2\"}");
        Thread.sleep(150);
        List<JsonNode> rest = sessions.drain("act2");
        assertThat(rest).noneMatch(n -> "GOAL_CANCELLED".equals(n.path("op").asText()));
        assertThat(repository.getGoalStatus("act2", "g2")).isEqualTo("SUCCEEDED");
    }

    @Test
    void resumeAfterReconnectDeliversOnlyMessagesAboveAckCursor() throws Exception {
        String sid1 = connect("res");
        gateway.handleText("res", subscribe("res", "/resume", "std_msgs/String", null));
        assertThat(sessions.awaitFrame("res", "CONNACK", 1000)).isNotNull();
        assertThat(sessions.awaitFrame("res", "SUBSCRIBED", 1000)).isNotNull();

        publish("/resume", "std_msgs/String", "{}", 1);
        publish("/resume", "std_msgs/String", "{}", 2);
        JsonNode m1 = sessions.awaitFrame("res", "TOPIC", 1000);
        JsonNode m2 = sessions.awaitFrame("res", "TOPIC", 1000);
        assertThat(m1.path("cursor").asLong()).isEqualTo(1);
        assertThat(m2.path("cursor").asLong()).isEqualTo(2);

        // ACK 到游标 2；之后的 3、4 未确认
        gateway.handleText("res", "{\"op\":\"ACK\",\"topic\":\"/resume\",\"cursor\":2}");
        publish("/resume", "std_msgs/String", "{}", 3);
        publish("/resume", "std_msgs/String", "{}", 4);
        assertThat(sessions.awaitFrame("res", "TOPIC", 1000).path("cursor").asLong()).isEqualTo(3);
        assertThat(sessions.awaitFrame("res", "TOPIC", 1000).path("cursor").asLong()).isEqualTo(4);

        // 断连并在宽限期内以同一 clientId 重连
        gateway.disconnect("res", sid1);
        Thread.sleep(50);
        connect("res");
        gateway.handleText("res", subscribe("res", "/resume", "std_msgs/String", null));
        assertThat(sessions.awaitFrame("res", "CONNACK", 1000)).isNotNull();
        assertThat(sessions.awaitFrame("res", "SUBSCRIBED", 1000)).isNotNull();

        // 只补发严格大于 ACK(2) 的消息，且标记 resume=true；不重复 1、2
        JsonNode r1 = sessions.awaitFrame("res", "TOPIC", 1000);
        JsonNode r2 = sessions.awaitFrame("res", "TOPIC", 1000);
        assertThat(r1.path("cursor").asLong()).isEqualTo(3);
        assertThat(r1.path("resume").asBoolean()).isTrue();
        assertThat(r2.path("cursor").asLong()).isEqualTo(4);
        assertThat(r2.path("resume").asBoolean()).isTrue();

        publish("/resume", "std_msgs/String", "{}", 5);
        JsonNode live = sessions.awaitFrame("res", "TOPIC", 1000);
        assertThat(live.path("cursor").asLong()).isEqualTo(5);
        assertThat(live.path("resume").asBoolean()).isFalse();

        // 全新客户端首次订阅不回放历史
        connect("fresh");
        gateway.handleText("fresh", subscribe("fresh", "/resume", "std_msgs/String", null));
        assertThat(sessions.awaitFrame("fresh", "CONNACK", 1000)).isNotNull();
        assertThat(sessions.awaitFrame("fresh", "SUBSCRIBED", 1000)).isNotNull();
        Thread.sleep(150);
        List<JsonNode> rest = sessions.drain("fresh");
        assertThat(rest).noneMatch(n -> "TOPIC".equals(n.path("op").asText()));
    }

    @Test
    void concurrentSubscribesShareOneUpstreamAndConcurrentUnsubscribesReleaseIt() throws Exception {
        int n = 8;
        List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            Thread t = new Thread(() -> {
                connect("cc" + idx);
                gateway.handleText("cc" + idx,
                        subscribe("cc" + idx, "/concurrent", "std_msgs/String",
                                "{\"depth\":10,\"reliability\":\"reliable\"}"));
            });
            threads.add(t);
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join(5000);
        }

        SubscriptionKey key = new SubscriptionKey("/concurrent", "std_msgs/String",
                "{\"depth\":10,\"reliability\":\"reliable\"}");
        pollAssert(() -> ros.activeSubscriptionCount() == 1, 2000, "single upstream under concurrency");
        assertThat(repository.getRefcount(key)).isEqualTo(n);

        // 每个客户端都收到同一游标
        publish("/concurrent", "std_msgs/String", key.qosJson(), 1);
        for (int i = 0; i < n; i++) {
            JsonNode f = sessions.awaitFrame("cc" + i, "TOPIC", 2000);
            assertThat(f).isNotNull();
            assertThat(f.path("cursor").asLong()).isEqualTo(1);
        }

        // 并发退订：只有最后一个释放者会安排宽限释放
        List<Thread> leavers = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            Thread t = new Thread(() -> gateway.handleText("cc" + idx,
                    "{\"op\":\"UNSUBSCRIBE\",\"topic\":\"/concurrent\"}"));
            leavers.add(t);
        }
        leavers.forEach(Thread::start);
        for (Thread t : leavers) {
            t.join(5000);
        }
        pollAssert(() -> ros.activeSubscriptionCount() == 0, 3000, "released after concurrent leaves");
        assertThat(repository.getRefcount(key)).isZero();
    }

    @Test
    void malformedFrameProducesError() throws Exception {
        connect("bad");
        gateway.handleText("bad", "not-json");
        gateway.handleText("bad", "{\"op\":\"WAT\"}");
        assertThat(sessions.awaitFrame("bad", "CONNACK", 1000)).isNotNull();
        JsonNode e1 = sessions.awaitFrame("bad", "ERROR", 1000);
        JsonNode e2 = sessions.awaitFrame("bad", "ERROR", 1000);
        assertThat(e1.path("code").asText()).isEqualTo("BAD_FRAME");
        assertThat(e2.path("code").asText()).isEqualTo("UNKNOWN_OP");
    }
}
