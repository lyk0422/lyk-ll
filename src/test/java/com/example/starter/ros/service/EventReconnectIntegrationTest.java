package com.example.starter.ros.service;

import com.example.starter.ros.RosIntegrationTestBase;
import com.example.starter.ros.bootstrap.DemoDataInitializer;
import com.example.starter.ros.dto.HotUpdateRequest;
import com.example.starter.ros.dto.ParamEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单调游标事件流：ACK 保存位置；同一 JVM 内断线重连只续传大于已确认游标的事件，不重复。
 */
class EventReconnectIntegrationTest extends RosIntegrationTestBase {

    @Autowired
    private HotUpdateService hotUpdateService;

    @Autowired
    private EventStreamService eventStreamService;

    private HotUpdateRequest singleNodeSuccess(String txId) {
        HotUpdateRequest.NodeChangeSet camera = new HotUpdateRequest.NodeChangeSet(
                DemoDataInitializer.CAMERA_NODE, 0L,
                Map.of("vision", Map.of("fps", 30)));
        return new HotUpdateRequest(txId, List.of(camera), 1000L);
    }

    @Test
    void pullAckReconnectDeliversOnlyNewerEventsWithoutDuplicates() {
        final String subscriber = "sub-A";

        // 尚无事件
        assertThat(eventStreamService.pull(subscriber, 100)).isEmpty();
        assertThat(eventStreamService.ackedCursor(subscriber)).isZero();

        // 事务 1 -> 事件游标 1
        hotUpdateService.update(singleNodeSuccess("tx-evt-1"));
        List<ParamEvent> firstPull = eventStreamService.pull(subscriber, 100);
        assertThat(firstPull).hasSize(1);
        assertThat(firstPull.get(0).cursor()).isEqualTo(1L);
        assertThat(firstPull.get(0).type()).isEqualTo(ParamEvent.TYPE_COMMITTED);
        assertThat(firstPull.get(0).transactionId()).isEqualTo("tx-evt-1");

        // 未 ACK 前“断线重连”：再次拉取仍是同一批（游标 > 已确认位置 0）
        List<ParamEvent> reconnectBeforeAck = eventStreamService.pull(subscriber, 100);
        assertThat(reconnectBeforeAck).extracting(ParamEvent::cursor).containsExactly(1L);

        // ACK 游标 1
        eventStreamService.ack(subscriber, 1L);
        assertThat(eventStreamService.ackedCursor(subscriber)).isEqualTo(1L);

        // 事务 2 前 camera 已在版本 1，必须基于最新期望版本才能提交
        HotUpdateRequest.NodeChangeSet cameraV2 = new HotUpdateRequest.NodeChangeSet(
                DemoDataInitializer.CAMERA_NODE, 1L,
                Map.of("vision", Map.of("fps", 24)));
        hotUpdateService.update(new HotUpdateRequest("tx-evt-2",
                List.of(cameraV2), 1000L));

        // ACK 后重连：只续传大于 1 的事件，游标单调，不重复
        List<ParamEvent> afterAck = eventStreamService.pull(subscriber, 100);
        assertThat(afterAck).extracting(ParamEvent::cursor).containsExactly(2L);
        assertThat(afterAck.get(0).transactionId()).isEqualTo("tx-evt-2");

        // ACK 单调不回退：先确认到 2，再用更小的 1 确认，位置仍停留在 2
        eventStreamService.ack(subscriber, 2L);
        eventStreamService.ack(subscriber, 1L);
        assertThat(eventStreamService.ackedCursor(subscriber)).isEqualTo(2L);
        // 再次拉取无新事件
        assertThat(eventStreamService.pull(subscriber, 100)).isEmpty();
    }

    @Test
    void eventCursorsAreMonotonicAcrossTransactions() {
        hotUpdateService.update(singleNodeSuccess("tx-cursor-1"));
        HotUpdateRequest.NodeChangeSet cameraV2 = new HotUpdateRequest.NodeChangeSet(
                DemoDataInitializer.CAMERA_NODE, 1L,
                Map.of("vision", Map.of("fps", 24)));
        hotUpdateService.update(new HotUpdateRequest("tx-cursor-2",
                List.of(cameraV2), 1000L));

        List<ParamEvent> all = eventStreamService.pull("fresh-sub", 100);
        assertThat(all).extracting(ParamEvent::cursor).containsExactly(1L, 2L);
        assertThat(all).extracting(ParamEvent::transactionId)
                .containsExactly("tx-cursor-1", "tx-cursor-2");
    }

    @Test
    void failedTransactionsDoNotAppendEvents() {
        nodeAdapter.configure(DemoDataInitializer.CAMERA_NODE,
                new com.example.starter.ros.node.NodeBehavior(
                        com.example.starter.ros.node.NodeBehavior.Mode.REJECT, "拒绝", 0L));
        hotUpdateService.update(singleNodeSuccess("tx-no-event"));

        assertThat(eventStreamService.pull("sub-failed", 100)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ros_param_event", Integer.class)).isZero();
    }
}
