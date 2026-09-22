package com.example.starter.ros;

import com.example.starter.ros.api.EventView;
import com.example.starter.ros.api.NodeView;
import com.example.starter.ros.api.RegisterNodeRequest;
import com.example.starter.ros.api.ResourceSpec;
import com.example.starter.ros.domain.RegistryErrorCode;
import com.example.starter.ros.domain.RegistryException;
import com.example.starter.ros.service.NodeRegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 节点异常退出模拟与注销的 H2 集成测试。
 */
class CrashAndDeregisterTest extends AbstractRegistryIntegrationTest {

    @Autowired
    private NodeRegistryService service;

    private NodeView register(String nodeId, String requestId) {
        return service.register(new RegisterNodeRequest(nodeId, "/robot", "crash_node",
                List.of(
                        new ResourceSpec("SERVICE", "/robot/svc", "example/srv/Trigger"),
                        new ResourceSpec("ACTION", "/robot/action", "example/action/Nav"),
                        new ResourceSpec("TOPIC", "/robot/pose", "std_msgs/msg/Pose")),
                requestId));
    }

    @Test
    void crash_clears_all_resources_in_one_transaction_and_publishes_single_cleanup_event() {
        register("node-1", "req-reg");

        NodeView crashed = service.crash("node-1", "req-crash");
        assertFalse(crashed.alive());
        assertTrue(crashed.resources().isEmpty());

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM node_resource WHERE node_id = 'node-1'", Integer.class);
        assertEquals(0, remaining, "崩溃事务必须清理全部资源");

        Integer cleanups = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM resource_event WHERE node_id = 'node-1' "
                        + "AND event_type = 'NODE_CRASH_CLEANUP'", Integer.class);
        assertEquals(1, cleanups, "崩溃清理事件只发布一次");

        // 清理事件 detail 记录释放资源数
        String detail = jdbcTemplate.queryForObject(
                "SELECT detail FROM resource_event WHERE event_type = 'NODE_CRASH_CLEANUP'",
                String.class);
        assertTrue(detail.contains("released 3 resource(s)"));
    }

    @Test
    void crash_replay_with_same_requestId_returns_stored_result_and_no_second_cleanup() {
        register("node-1", "req-reg");
        NodeView first = service.crash("node-1", "req-crash");
        NodeView replay = service.crash("node-1", "req-crash");

        assertEquals(first, replay);
        Integer cleanups = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM resource_event WHERE event_type = 'NODE_CRASH_CLEANUP'",
                Integer.class);
        assertEquals(1, cleanups);
    }

    @Test
    void second_crash_with_new_requestId_is_rejected_without_new_event() {
        register("node-1", "req-reg");
        service.crash("node-1", "req-crash-1");

        RegistryException ex = assertThrows(RegistryException.class,
                () -> service.crash("node-1", "req-crash-2"));
        assertEquals(RegistryErrorCode.NODE_CRASHED, ex.getErrorCode());

        Integer cleanups = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM resource_event WHERE event_type = 'NODE_CRASH_CLEANUP'",
                Integer.class);
        assertEquals(1, cleanups, "重复崩溃不得再发清理事件");
    }

    @Test
    void exclusive_name_becomes_available_after_crash() {
        register("node-1", "req-reg-1");
        service.crash("node-1", "req-crash");

        // 崩溃节点释放互斥名后，存活节点可声明同名 Service/Action
        NodeView node2 = register("node-2", "req-reg-2");
        assertEquals(3, node2.resources().size());
        assertTrue(node2.alive());
    }

    @Test
    void crashed_node_can_reregister_with_same_nodeId_as_new_lifecycle() {
        register("node-1", "req-reg-1");
        service.crash("node-1", "req-crash");

        NodeView reborn = service.register(new RegisterNodeRequest("node-1", "/robot",
                "crash_node", List.of(new ResourceSpec("TOPIC", "/robot/pose",
                "std_msgs/msg/Pose")), "req-reg-reborn"));
        assertTrue(reborn.alive());
        assertEquals("UNCONFIGURED", reborn.lifecycleState());
        assertEquals(1L, reborn.version());
        assertEquals(1, reborn.resources().size());
    }

    @Test
    void deregister_releases_resources_deletes_node_and_emits_release_events() {
        register("node-1", "req-reg");
        NodeView deregistered = service.deregister("node-1", "req-dereg");
        assertEquals("node-1", deregistered.nodeId());
        assertEquals(3, deregistered.resources().size(), "注销响应返回注销前持有的资源快照");

        Integer nodeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM ros_node WHERE node_id = 'node-1'", Integer.class);
        Integer resourceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM node_resource WHERE node_id = 'node-1'", Integer.class);
        assertEquals(0, nodeCount);
        assertEquals(0, resourceCount);

        Integer releases = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM resource_event WHERE event_type = 'RESOURCE_RELEASED'",
                Integer.class);
        assertEquals(3, releases);
    }

    @Test
    void duplicate_deregister_with_new_requestId_returns_not_found_without_duplicate_events() {
        register("node-1", "req-reg");
        service.deregister("node-1", "req-dereg-1");

        RegistryException ex = assertThrows(RegistryException.class,
                () -> service.deregister("node-1", "req-dereg-2"));
        assertEquals(RegistryErrorCode.NODE_NOT_FOUND, ex.getErrorCode());

        Integer releases = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM resource_event WHERE event_type = 'RESOURCE_RELEASED'",
                Integer.class);
        assertEquals(3, releases, "重复注销不得产生重复资源释放事件");
    }

    @Test
    void deregister_replay_returns_original_snapshot() {
        register("node-1", "req-reg");
        NodeView first = service.deregister("node-1", "req-dereg");
        NodeView replay = service.deregister("node-1", "req-dereg");
        assertEquals(first, replay);
    }

    @Test
    void event_revisions_are_gapless_and_globally_increasing() {
        register("node-1", "req-reg-1");
        service.register(new RegisterNodeRequest("node-2", "/robot", "other_node",
                List.of(
                        new ResourceSpec("SERVICE", "/robot/svc-2", "example/srv/Trigger"),
                        new ResourceSpec("TOPIC", "/robot/tick", "std_msgs/msg/Empty")),
                "req-reg-2"));
        service.crash("node-1", "req-crash-1");
        service.deregister("node-2", "req-dereg-2");

        List<EventView> events = service.eventsAfter(0, 100);
        long expected = 1;
        for (EventView event : events) {
            assertEquals(expected, event.revision());
            expected++;
        }
        // node-1 声明 3 + node-2 声明 2 + 崩溃清理 1 + node-2 注销释放 2 = 8
        assertEquals(8, events.size());
    }
}
