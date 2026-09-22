package com.example.starter.ros;

import com.example.starter.ros.api.EventView;
import com.example.starter.ros.service.SubscriberProjection;
import com.example.starter.ros.service.SubscriberProjection.DeliveryOutcome;
import com.example.starter.ros.service.SubscriberProjection.DeliveryResult;
import com.example.starter.ros.service.SubscriberProjection.NodeResources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订阅方按递增 revision 消费事件的纯单元测试：乱序/重复/跳号均不得令本地视图回退。
 */
class SubscriberProjectionTest {

    private SubscriberProjection projection;

    @BeforeEach
    void setUp() {
        projection = new SubscriberProjection();
    }

    private EventView declared(long revision, String node, String category, String name) {
        return new EventView(revision, node, "RESOURCE_DECLARED", category, name,
                "std_msgs/msg/Empty", null);
    }

    private EventView released(long revision, String node, String category, String name) {
        return new EventView(revision, node, "RESOURCE_RELEASED", category, name,
                "std_msgs/msg/Empty", "deregister");
    }

    private EventView crashCleanup(long revision, String node) {
        return new EventView(revision, node, "NODE_CRASH_CLEANUP", null, null, null,
                "crashed; released 1 resource(s)");
    }

    @Test
    void in_order_events_advance_view_and_revision() {
        DeliveryOutcome o1 = projection.applyOne(declared(1, "n1", "TOPIC", "/a"));
        DeliveryOutcome o2 = projection.applyOne(declared(2, "n1", "TOPIC", "/b"));

        assertEquals(DeliveryOutcome.APPLIED, o1);
        assertEquals(DeliveryOutcome.APPLIED, o2);
        assertEquals(2L, projection.getLastRevision());
        assertEquals(2, projection.snapshot().nodes().get(0).resources().size());
    }

    @Test
    void duplicate_and_old_events_are_ignored_without_view_change() {
        projection.applyOne(declared(1, "n1", "TOPIC", "/a"));
        projection.applyOne(declared(2, "n1", "TOPIC", "/b"));

        // 重复旧事件（revision=1）即使内容是“释放”，也绝不能回退视图
        assertEquals(DeliveryOutcome.IGNORED,
                projection.applyOne(released(1, "n1", "TOPIC", "/a")));
        assertEquals(DeliveryOutcome.IGNORED,
                projection.applyOne(declared(2, "n1", "TOPIC", "/b")));

        assertEquals(2L, projection.getLastRevision());
        NodeResources node = projection.snapshot().nodes().get(0);
        assertEquals(2, node.resources().size(), "乱序旧事件不得令本地视图回退");
    }

    @Test
    void out_of_order_gap_events_are_rejected_until_missing_revision_arrives() {
        projection.applyOne(declared(1, "n1", "TOPIC", "/a"));

        // revision=3 先到（缺少 2），拒绝且不改变水位与视图
        assertEquals(DeliveryOutcome.REJECTED,
                projection.applyOne(declared(3, "n1", "TOPIC", "/c")));
        assertEquals(1L, projection.getLastRevision());
        assertEquals(1, projection.snapshot().nodes().get(0).resources().size());

        // revision=2 到达后正常应用；此后 3 可应用
        assertEquals(DeliveryOutcome.APPLIED,
                projection.applyOne(declared(2, "n1", "TOPIC", "/b")));
        assertEquals(DeliveryOutcome.APPLIED,
                projection.applyOne(declared(3, "n1", "TOPIC", "/c")));
        assertEquals(3L, projection.getLastRevision());
        assertEquals(3, projection.snapshot().nodes().get(0).resources().size());
    }

    @Test
    void shuffled_batch_applies_only_contiguous_prefix_and_counts_outcomes() {
        List<EventView> shuffled = List.of(
                declared(3, "n1", "TOPIC", "/c"),
                declared(1, "n1", "TOPIC", "/a"),
                declared(1, "n1", "TOPIC", "/a"),
                declared(2, "n1", "TOPIC", "/b"));

        // 乱序批处理：3 先到被拒绝；1 应用；重复 1 忽略；2 应用
        DeliveryResult result = projection.deliver(shuffled);
        assertEquals(2, result.applied());
        assertEquals(1, result.ignored());
        assertEquals(1, result.rejected());
        assertEquals(2L, result.lastRevision());
        assertEquals(2, result.nodes().get(0).resources().size());

        // 补齐缺失事件后视图最终一致
        DeliveryResult followUp = projection.deliver(List.of(declared(3, "n1", "TOPIC", "/c")));
        assertEquals(1, followUp.applied());
        assertEquals(3, followUp.nodes().get(0).resources().size());
    }

    @Test
    void crash_cleanup_event_releases_all_node_resources_once() {
        projection.applyOne(declared(1, "n1", "SERVICE", "/svc"));
        projection.applyOne(declared(2, "n1", "ACTION", "/act"));
        projection.applyOne(crashCleanup(3, "n1"));
        // 重复清理事件忽略，不产生第二次清理效果
        assertEquals(DeliveryOutcome.IGNORED, projection.applyOne(crashCleanup(3, "n1")));

        assertTrue(projection.snapshot().nodes().isEmpty(),
                "崩溃清理后订阅方视图中该节点资源应全部释放");
        assertEquals(3L, projection.getLastRevision());
    }
}
