package com.example.starter.ros;

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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 节点注册主流程、资源互斥与 requestId 幂等的 H2 集成测试。
 */
class NodeRegistrationTest extends AbstractRegistryIntegrationTest {

    @Autowired
    private NodeRegistryService service;

    private RegisterNodeRequest request(String nodeId, String requestId, ResourceSpec... resources) {
        return new RegisterNodeRequest(nodeId, "/robot", "nav_node",
                List.of(resources), requestId);
    }

    private ResourceSpec service(String name) {
        return new ResourceSpec("SERVICE", name, "example/srv/Trigger");
    }

    @Test
    void register_creates_node_with_declared_resources_and_unconfigured_state() {
        NodeView view = service.register(request("node-1", "req-1",
                new ResourceSpec("TOPIC", "/robot/cmd_vel", "std_msgs/msg/String"),
                service("/robot/trigger")));

        assertEquals("node-1", view.nodeId());
        assertEquals("UNCONFIGURED", view.lifecycleState());
        assertEquals(1L, view.version());
        assertTrue(view.alive());
        assertEquals(2, view.resources().size());

        // 每个声明产生一个递增 revision 的事件
        Integer events = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM resource_event WHERE node_id = ? AND event_type = 'RESOURCE_DECLARED'",
                Integer.class, "node-1");
        assertEquals(2, events);
    }

    @Test
    void duplicate_nodeId_registration_is_rejected_without_extra_resources_or_events() {
        service.register(request("node-1", "req-1", service("/svc/a")));

        RegistryException ex = assertThrows(RegistryException.class,
                () -> service.register(request("node-1", "req-other", service("/svc/a"))));
        assertEquals(RegistryErrorCode.NODE_ALREADY_REGISTERED, ex.getErrorCode());

        Integer resources = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM node_resource", Integer.class);
        Integer events = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM resource_event", Integer.class);
        assertEquals(1, resources);
        assertEquals(1, events);
    }

    @Test
    void same_service_name_cannot_be_held_by_two_live_nodes_but_topic_can_be_shared() {
        service.register(request("node-1", "req-1",
                service("/shared/svc"),
                new ResourceSpec("TOPIC", "/shared/topic", "std_msgs/msg/String")));

        RegistryException ex = assertThrows(RegistryException.class,
                () -> service.register(request("node-2", "req-2", service("/shared/svc"))));
        assertEquals(RegistryErrorCode.RESOURCE_CONFLICT, ex.getErrorCode());

        // 失败事务完全回滚：node-2 及其资源均不存在
        Integer node2 = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM ros_node WHERE node_id = 'node-2'", Integer.class);
        assertEquals(0, node2);

        // TOPIC 同名允许跨节点共享
        NodeView node3 = service.register(request("node-3", "req-3",
                new ResourceSpec("TOPIC", "/shared/topic", "std_msgs/msg/String")));
        assertEquals(1, node3.resources().size());
    }

    @Test
    void same_requestId_replay_returns_original_result_without_duplicate_rows() {
        RegisterNodeRequest first = request("node-1", "req-idem", service("/svc/a"));
        NodeView firstView = service.register(first);

        NodeView replayView = service.register(request("node-1", "req-idem", service("/svc/a")));
        assertNotSame(firstView, replayView);
        assertEquals(firstView, replayView);

        Integer resources = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM node_resource", Integer.class);
        Integer events = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM resource_event", Integer.class);
        Integer requests = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request", Integer.class);
        assertEquals(1, resources, "重放不得产生重复资源");
        assertEquals(1, events, "重放不得产生重复事件");
        assertEquals(1, requests, "重放不得产生重复幂等记录");
    }

    @Test
    void same_requestId_with_different_parameters_returns_conflict() {
        service.register(request("node-1", "req-idem", service("/svc/a")));

        // 同一 requestId，但声明了不同参数（另一个 service）
        RegistryException ex = assertThrows(RegistryException.class,
                () -> service.register(request("node-1", "req-idem", service("/svc/different"))));
        assertEquals(RegistryErrorCode.IDEMPOTENCY_PARAM_MISMATCH, ex.getErrorCode());
    }

    @Test
    void failed_register_stored_and_replayed_with_same_error_code() {
        service.register(request("node-1", "req-1", service("/svc/a")));
        // node-2 与 node-1 互斥冲突，该失败结果按 requestId 记录
        RegisterNodeRequest failing = request("node-2", "req-fail", service("/svc/a"));
        RegistryException first = assertThrows(RegistryException.class, () -> service.register(failing));
        assertEquals(RegistryErrorCode.RESOURCE_CONFLICT, first.getErrorCode());

        // 同参重放仍返回原失败语义，且不产生任何新事件
        RegistryException replay = assertThrows(RegistryException.class,
                () -> service.register(request("node-2", "req-fail", service("/svc/a"))));
        assertEquals(RegistryErrorCode.RESOURCE_CONFLICT, replay.getErrorCode());

        Integer events = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM resource_event", Integer.class);
        assertEquals(1, events);
    }
}
