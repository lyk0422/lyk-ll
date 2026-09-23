package com.example.starter.rosbridge.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.rosbridge.protocol.Protocol;
import com.example.starter.rosbridge.ros.GoalOutcome;
import com.example.starter.rosbridge.ros.Qos;
import com.example.starter.rosbridge.ros.ServiceOutcome;
import com.example.starter.rosbridge.store.ClientSubscriptionRow;
import com.example.starter.rosbridge.store.SharedSubscriptionRow;
import com.example.starter.rosbridge.testsupport.GatewayHarness;
import com.example.starter.rosbridge.testsupport.RecordingTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 网关端到端业务状态机测试：真实 H2 状态 + 合成 ROS 适配器 + 可控时钟，
 * 不启动 Web 容器，覆盖题目要求的主流程、失败分支与幂等/并发边界。
 */
class GatewayServiceTest {

    private static final String TOPIC = "/chatter";
    private static final String TYPE = "std_msgs/String";
    private static final Qos QOS = Qos.defaults();

    private final List<GatewayHarness> harnesses = new ArrayList<>();

    private GatewayHarness harness() {
        GatewayHarness h = GatewayHarness.create();
        harnesses.add(h);
        return h;
    }

    @AfterEach
    void tearDown() {
        for (GatewayHarness h : harnesses) {
            h.close();
        }
    }

    private static List<JsonNode> frames(RecordingTransport t) {
        List<JsonNode> out = new ArrayList<>();
        for (String s : t.sent()) {
            try {
                out.add(Protocol.MAPPER.readTree(s));
            } catch (Exception ignored) {
                // 非 JSON 不应出现
            }
        }
        return out;
    }

    private static List<JsonNode> byOp(RecordingTransport t, String op) {
        return frames(t).stream().filter(f -> op.equals(f.path("op").asText())).toList();
    }

    private static List<Long> cursors(RecordingTransport t) {
        return byOp(t, Protocol.OP_PUBLISH).stream()
                .map(f -> f.path("cursor").asLong()).toList();
    }

    private SharedSubscriptionRow shared(GatewayHarness h) {
        return h.repo.findShared(TOPIC, TYPE, QOS.key());
    }

    private void emit(GatewayHarness h, int n) {
        for (int i = 1; i <= n; i++) {
            ObjectNode msg = JsonNodeFactory.instance.objectNode().put("data", "m" + i);
            h.adapter.emit(TOPIC, msg);
        }
    }

    // ---------------- Topic：共享订阅 / 引用计数 / 游标 ----------------

    @Test
    void multipleClientsShareOneUpstreamAndSeeSameCursors() {
        GatewayHarness h = harness();
        RecordingTransport a = h.connect("a");
        RecordingTransport b = h.connect("b");
        h.service.subscribe("a", TOPIC, TYPE, QOS);
        h.service.subscribe("b", TOPIC, TYPE, QOS);

        // 同一 topic+type+qos 只建立一个上游订阅
        assertThat(h.adapter.activeSubscriptionCount(TOPIC)).isEqualTo(1);
        SharedSubscriptionRow s = shared(h);
        assertThat(s.refCount()).isEqualTo(2);

        emit(h, 3);
        assertThat(cursors(a)).containsExactly(1L, 2L, 3L);
        assertThat(cursors(b)).containsExactly(1L, 2L, 3L);
    }

    @Test
    void differentQosCreatesSeparateUpstream() {
        GatewayHarness h = harness();
        h.connect("a");
        h.service.subscribe("a", TOPIC, TYPE, Qos.defaults());
        h.service.subscribe("a", TOPIC, TYPE,
                new Qos(Qos.BEST_EFFORT, Qos.VOLATILE, 10));
        assertThat(h.adapter.activeSubscriptions()).hasSize(2);
    }

    @Test
    void duplicateSubscribeIsIdempotent() {
        GatewayHarness h = harness();
        h.connect("a");
        h.service.subscribe("a", TOPIC, TYPE, QOS);
        h.service.subscribe("a", TOPIC, TYPE, QOS);
        assertThat(shared(h).refCount()).isEqualTo(1);
        assertThat(h.adapter.activeSubscriptions()).hasSize(1);
    }

    @Test
    void lastUnsubscribeReleasesUpstreamOnlyAfterGrace() {
        GatewayHarness h = harness();
        h.clock.setFixedMillis(0);
        h.connect("a");
        h.connect("b");
        h.service.subscribe("a", TOPIC, TYPE, QOS);
        h.service.subscribe("b", TOPIC, TYPE, QOS);

        h.service.unsubscribe("a", TOPIC, TYPE, QOS);
        assertThat(shared(h).refCount()).isEqualTo(1);
        // 一个客户端取消后上游仍保留
        h.service.runMaintenance();
        assertThat(h.adapter.activeSubscriptions()).hasSize(1);

        h.service.unsubscribe("b", TOPIC, TYPE, QOS);
        assertThat(shared(h).refCount()).isZero();

        // 宽限期内不释放
        h.clock.setFixedMillis(400);
        h.service.runMaintenance();
        assertThat(h.adapter.activeSubscriptions()).hasSize(1);

        // 宽限期结束后释放
        h.clock.setFixedMillis(400 + h.props.graceMs() + 1);
        h.service.runMaintenance();
        assertThat(h.adapter.activeSubscriptions()).isEmpty();
        assertThat(shared(h).upstreamActive()).isFalse();

        // 重复取消幂等，不报错、不产生负引用
        h.service.unsubscribe("a", TOPIC, TYPE, QOS);
        assertThat(shared(h).refCount()).isZero();
    }

    @Test
    void resubscribeWithinGraceReusesUpstream() {
        GatewayHarness h = harness();
        h.clock.setFixedMillis(0);
        h.connect("a");
        h.connect("b");
        h.service.subscribe("a", TOPIC, TYPE, QOS);
        h.service.subscribe("b", TOPIC, TYPE, QOS);
        h.service.unsubscribe("a", TOPIC, TYPE, QOS);
        h.service.unsubscribe("b", TOPIC, TYPE, QOS);
        assertThat(shared(h).refCount()).isZero();

        // 宽限期内 a 重新订阅：复用上游，不新建
        h.service.subscribe("a", TOPIC, TYPE, QOS);
        assertThat(shared(h).refCount()).isEqualTo(1);
        assertThat(h.adapter.activeSubscriptions()).hasSize(1);

        h.clock.setFixedMillis(1000 + h.props.graceMs() + 1);
        h.service.runMaintenance();
        // 仍有引用，不释放
        assertThat(h.adapter.activeSubscriptions()).hasSize(1);
    }

    // ---------------- 慢消费者隔离 ----------------

    @Test
    void slowConsumerDropsOldestAndSendsResumeGapWithoutAffectingOthers() {
        GatewayHarness h = harness();
        // 慢客户端：队列上限 4，且连接后只允许发送前 2 帧（connected + subscribed）
        RecordingTransport slow = h.connect("slow", null, 4);
        slow.setFailAfter(2);
        RecordingTransport fast = h.connect("fast");
        h.service.subscribe("slow", TOPIC, TYPE, QOS);
        h.service.subscribe("fast", TOPIC, TYPE, QOS);
        // 清空快速客户端的控制帧干扰
        fast.clear();

        // 队列上限 4：投递 8 条后，最旧的 1..4 被合并为一个缺口，仅保留 5..8
        emit(h, 8);

        assertThat(h.service.session("slow").queuedSize()).isEqualTo(4);
        assertThat(h.service.session("slow").gapCount()).isEqualTo(1);
        // 恢复发送
        slow.setFailAfter(Integer.MAX_VALUE);
        h.service.session("slow").pump();

        List<JsonNode> gaps = byOp(slow, Protocol.OP_RESUME_GAP);
        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).path("fromCursor").asLong()).isEqualTo(1L);
        assertThat(gaps.get(0).path("toCursor").asLong()).isEqualTo(4L);
        assertThat(cursors(slow)).containsExactly(5L, 6L, 7L, 8L);

        // 快速客户端不受影响，收到完整序列
        assertThat(cursors(fast)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
    }

    // ---------------- ACK 与断线重连 ----------------

    @Test
    void reconnectResumesAfterAckedCursorWithoutRedelivery() {
        GatewayHarness h = harness();
        RecordingTransport first = h.connect("c");
        h.service.subscribe("c", TOPIC, TYPE, QOS);
        emit(h, 3);
        assertThat(cursors(first)).containsExactly(1L, 2L, 3L);

        // 确认到游标 3
        h.service.ack("c", TOPIC, TYPE, QOS, 3L);
        ClientSubscriptionRow cs = h.repo.findClientSub("c", shared(h).id());
        assertThat(cs.lastAckCursor()).isEqualTo(3L);

        // 断连，宽限期内上游保留，期间到达 m4、m5（留存但不在线扇出）
        h.service.disconnect(first);
        assertThat(h.adapter.activeSubscriptions()).hasSize(1);
        emit(h, 2);
        assertThat(byOp(first, Protocol.OP_PUBLISH)).hasSize(3);

        // 同 JVM 重连：仅投递游标严格大于 3 的消息，不重复已确认消息
        RecordingTransport second = h.connect("c");
        List<JsonNode> publishes = byOp(second, Protocol.OP_PUBLISH);
        assertThat(publishes).extracting(f -> f.path("cursor").asLong())
                .containsExactly(4L, 5L);
    }

    @Test
    void unackedMessageIsRedeliveredAfterReconnect() {
        GatewayHarness h = harness();
        RecordingTransport first = h.connect("c");
        h.service.subscribe("c", TOPIC, TYPE, QOS);
        emit(h, 2);
        // 只确认游标 1，游标 2 已投递但未确认
        h.service.ack("c", TOPIC, TYPE, QOS, 1L);
        h.service.disconnect(first);

        RecordingTransport second = h.connect("c");
        // 至少一次语义：未确认的游标 2 重投
        assertThat(cursors(second)).contains(2L);
        assertThat(cursors(second)).doesNotContain(1L);
    }

    @Test
    void afterGraceExpiresReconnectDoesNotResurrectUpstream() {
        GatewayHarness h = harness();
        h.clock.setFixedMillis(0);
        RecordingTransport first = h.connect("c");
        h.service.subscribe("c", TOPIC, TYPE, QOS);
        emit(h, 1);
        h.service.disconnect(first);

        h.clock.setFixedMillis(h.props.graceMs() + 1);
        h.service.runMaintenance();
        assertThat(h.adapter.activeSubscriptions()).isEmpty();

        // 重连不会自动恢复已释放的订阅；需重新订阅，且建立的是全新上游
        RecordingTransport second = h.connect("c");
        assertThat(byOp(second, Protocol.OP_PUBLISH)).isEmpty();
        h.service.subscribe("c", TOPIC, TYPE, QOS);
        assertThat(h.adapter.activeSubscriptions()).hasSize(1);
        emit(h, 1);
        // 新上游游标从 1 重新开始
        assertThat(cursors(second)).containsExactly(1L);
    }

    // ---------------- Service ----------------

    @Test
    void serviceSuccessReturnsOk() {
        GatewayHarness h = harness();
        RecordingTransport c = h.connect("c");
        ObjectNode args = JsonNodeFactory.instance.objectNode().put("x", 42);
        h.service.callService("c", "req-1", "/add", "srv/Srv", args, null);

        List<JsonNode> resp = byOp(c, Protocol.OP_SERVICE_RESPONSE);
        assertThat(resp).hasSize(1);
        assertThat(resp.get(0).path("status").asText()).isEqualTo(Protocol.SERVICE_OK);
        assertThat(resp.get(0).path("values").path("x").asInt()).isEqualTo(42);
    }

    @Test
    void serviceTimeoutThenLateResultDoesNotCompleteAgain() {
        GatewayHarness h = harness();
        h.adapter.setDeferServices(true);
        RecordingTransport c = h.connect("c");

        h.clock.setFixedMillis(1000);
        h.service.callService("c", "req-9", "/slow", "srv/Srv",
                JsonNodeFactory.instance.objectNode(), 1000L);
        // deadline = 2000；未到点不超时
        h.clock.setFixedMillis(1999);
        h.service.runMaintenance();
        assertThat(byOp(c, Protocol.OP_SERVICE_RESPONSE)).isEmpty();

        // 到点超时
        h.clock.setFixedMillis(2000);
        h.service.runMaintenance();
        List<JsonNode> timedOut = byOp(c, Protocol.OP_SERVICE_RESPONSE);
        assertThat(timedOut).hasSize(1);
        assertThat(timedOut.get(0).path("status").asText()).isEqualTo(Protocol.SERVICE_TIMEOUT);

        // 适配器迟到成功结果：CAS 失败，不得再次完成请求
        assertThat(h.adapter.deferredServices()).hasSize(1);
        h.adapter.completeDeferredService(0,
                ServiceOutcome.success(JsonNodeFactory.instance.objectNode().put("late", 1)));
        assertThat(byOp(c, Protocol.OP_SERVICE_RESPONSE)).hasSize(1);
        assertThat(h.repo.getServiceCall("c", "req-9").status())
                .isEqualTo(com.example.starter.rosbridge.store.ServiceCallRow.TIMEOUT);
    }

    @Test
    void duplicateServiceRequestDoesNotCallAdapterTwice() {
        GatewayHarness h = harness();
        h.adapter.setDeferServices(true);
        h.connect("c");
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        h.service.callService("c", "dup", "/s", "T", args, 5000L);
        h.service.callService("c", "dup", "/s", "T", args, 5000L);
        assertThat(h.adapter.deferredServices()).hasSize(1);
    }

    // ---------------- Action ----------------

    @Test
    void cancelActionIsIdempotentAndLateResultIgnored() {
        GatewayHarness h = harness();
        RecordingTransport c = h.connect("c");
        h.service.sendGoal("c", "g-1", "/nav", "act/Act",
                JsonNodeFactory.instance.objectNode().put("target", 9));

        var goal = h.adapter.goal(0);
        goal.feedback(JsonNodeFactory.instance.objectNode().put("pct", 50));
        assertThat(byOp(c, Protocol.OP_ACTION_FEEDBACK)).hasSize(1);

        // 取消：CAS 置 CANCELED，通知适配器
        h.service.cancelGoal("c", "g-1");
        assertThat(goal.canceled()).isTrue();
        List<JsonNode> results = byOp(c, Protocol.OP_ACTION_RESULT);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).path("status").asText()).isEqualTo(Protocol.GOAL_CANCELED);

        // 适配器的迟到成功结果被忽略，不产生 SUCCEEDED
        goal.succeed(JsonNodeFactory.instance.objectNode().put("done", true));
        assertThat(byOp(c, Protocol.OP_ACTION_RESULT)
                .stream().filter(f -> Protocol.GOAL_SUCCEEDED.equals(f.path("status").asText()))
                .count()).isZero();
        assertThat(h.repo.getGoal("c", "g-1").status())
                .isEqualTo(com.example.starter.rosbridge.store.ActionGoalRow.CANCELED);

        // 重复取消幂等：状态不变
        h.service.cancelGoal("c", "g-1");
        assertThat(h.repo.getGoal("c", "g-1").status())
                .isEqualTo(com.example.starter.rosbridge.store.ActionGoalRow.CANCELED);
    }

    @Test
    void goalResultWinsOverLateCancelAndFeedbackStopsAfterTerminal() {
        GatewayHarness h = harness();
        RecordingTransport c = h.connect("c");
        h.service.sendGoal("c", "g-2", "/nav", "act/Act",
                JsonNodeFactory.instance.objectNode());
        var goal = h.adapter.goal(0);

        goal.succeed(JsonNodeFactory.instance.objectNode().put("ok", true));
        assertThat(byOp(c, Protocol.OP_ACTION_RESULT)
                .get(0).path("status").asText()).isEqualTo(Protocol.GOAL_SUCCEEDED);

        // 终态后的 feedback 不再投递；晚到取消不能改变终态
        goal.feedback(JsonNodeFactory.instance.objectNode().put("pct", 99));
        h.service.cancelGoal("c", "g-2");
        assertThat(h.repo.getGoal("c", "g-2").status())
                .isEqualTo(com.example.starter.rosbridge.store.ActionGoalRow.SUCCEEDED);
        assertThat(byOp(c, Protocol.OP_ACTION_FEEDBACK)).isEmpty();
    }

    @Test
    void duplicateGoalIdIsNotSubmittedTwice() {
        GatewayHarness h = harness();
        h.connect("c");
        ObjectNode goal = JsonNodeFactory.instance.objectNode();
        h.service.sendGoal("c", "g", "/a", "T", goal);
        h.service.sendGoal("c", "g", "/a", "T", goal);
        assertThat(h.adapter.pendingGoals()).hasSize(1);
    }

    @Test
    void cancelUnknownGoalReturnsError() {
        GatewayHarness h = harness();
        RecordingTransport c = h.connect("c");
        h.service.cancelGoal("c", "nope");
        assertThat(byOp(c, Protocol.OP_ERROR)).hasSize(1);
    }

    @Test
    void cursorIsMonotonicAcrossSharedSubscription() {
        GatewayHarness h = harness();
        RecordingTransport a = h.connect("a");
        h.service.subscribe("a", TOPIC, TYPE, QOS);
        for (int i = 0; i < 50; i++) {
            h.adapter.emit(TOPIC, new IntNode(i));
        }
        List<Long> cs = cursors(a);
        assertThat(cs).hasSize(50);
        for (int i = 0; i < cs.size(); i++) {
            assertThat(cs.get(i)).isEqualTo(i + 1L);
        }
    }
}
