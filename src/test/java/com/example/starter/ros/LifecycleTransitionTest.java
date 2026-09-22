package com.example.starter.ros;

import com.example.starter.ros.api.NodeView;
import com.example.starter.ros.api.RegisterNodeRequest;
import com.example.starter.ros.api.ResourceSpec;
import com.example.starter.ros.api.TransitionRequest;
import com.example.starter.ros.domain.RegistryErrorCode;
import com.example.starter.ros.domain.RegistryException;
import com.example.starter.ros.service.NodeRegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 生命周期相邻迁移、版本冲突与终结语义的 H2 集成测试。
 */
class LifecycleTransitionTest extends AbstractRegistryIntegrationTest {

    @Autowired
    private NodeRegistryService service;

    private NodeView registerNode(String nodeId, String requestId) {
        return service.register(new RegisterNodeRequest(nodeId, "/robot", "lifecycle_node",
                List.of(new ResourceSpec("TOPIC", "/robot/heartbeat", "std_msgs/msg/Empty")),
                requestId));
    }

    @Test
    void only_adjacent_transitions_are_allowed_and_version_increments() {
        NodeView node = registerNode("node-1", "req-reg");
        assertEquals(1L, node.version());

        NodeView inactive = service.transition("node-1",
                new TransitionRequest("INACTIVE", 1L, "req-cfg"));
        assertEquals("INACTIVE", inactive.lifecycleState());
        assertEquals(2L, inactive.version());

        NodeView active = service.transition("node-1",
                new TransitionRequest("ACTIVE", 2L, "req-act"));
        assertEquals("ACTIVE", active.lifecycleState());
        assertEquals(3L, active.version());

        NodeView backToInactive = service.transition("node-1",
                new TransitionRequest("INACTIVE", 3L, "req-deact"));
        assertEquals("INACTIVE", backToInactive.lifecycleState());
        assertEquals(4L, backToInactive.version());

        NodeView unconfigured = service.transition("node-1",
                new TransitionRequest("UNCONFIGURED", 4L, "req-clean"));
        assertEquals("UNCONFIGURED", unconfigured.lifecycleState());
        assertEquals(5L, unconfigured.version());

        // 每次迁移发布一个 LIFECYCLE_TRANSITION 事件
        Integer transitions = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM resource_event WHERE event_type = 'LIFECYCLE_TRANSITION'",
                Integer.class);
        assertEquals(4, transitions);
    }

    @Test
    void skipping_states_is_illegal_transition() {
        registerNode("node-1", "req-reg");
        RegistryException ex = assertThrows(RegistryException.class,
                () -> service.transition("node-1",
                        new TransitionRequest("ACTIVE", 1L, "req-jump")));
        assertEquals(RegistryErrorCode.ILLEGAL_TRANSITION, ex.getErrorCode());
    }

    @Test
    void backward_transition_active_to_unconfigured_is_illegal() {
        registerNode("node-1", "req-reg");
        service.transition("node-1", new TransitionRequest("INACTIVE", 1L, "req-cfg"));
        service.transition("node-1", new TransitionRequest("ACTIVE", 2L, "req-act"));

        RegistryException ex = assertThrows(RegistryException.class,
                () -> service.transition("node-1",
                        new TransitionRequest("UNCONFIGURED", 3L, "req-back")));
        assertEquals(RegistryErrorCode.ILLEGAL_TRANSITION, ex.getErrorCode());
    }

    @Test
    void stale_version_is_distinguishable_from_illegal_transition() {
        registerNode("node-1", "req-reg");
        service.transition("node-1", new TransitionRequest("INACTIVE", 1L, "req-cfg"));

        // 客户端仍持旧版本 1
        RegistryException ex = assertThrows(RegistryException.class,
                () -> service.transition("node-1",
                        new TransitionRequest("ACTIVE", 1L, "req-stale")));
        assertEquals(RegistryErrorCode.VERSION_CONFLICT, ex.getErrorCode());
    }

    @Test
    void finalized_node_transition_is_distinguishable_and_state_remains_finalized() {
        registerNode("node-1", "req-reg");
        NodeView finalized = service.transition("node-1",
                new TransitionRequest("FINALIZED", 1L, "req-shutdown"));
        assertEquals("FINALIZED", finalized.lifecycleState());

        RegistryException ex = assertThrows(RegistryException.class,
                () -> service.transition("node-1",
                        new TransitionRequest("INACTIVE", 2L, "req-after-final")));
        assertEquals(RegistryErrorCode.NODE_FINALIZED, ex.getErrorCode());
    }

    @Test
    void can_shutdown_from_any_non_final_state() {
        NodeView node = registerNode("node-1", "req-reg");
        service.transition("node-1",
                new TransitionRequest("INACTIVE", node.version(), "req-cfg"));
        service.transition("node-1",
                new TransitionRequest("ACTIVE", 2L, "req-act"));

        NodeView finalized = service.transition("node-1",
                new TransitionRequest("FINALIZED", 3L, "req-shutdown"));
        assertEquals("FINALIZED", finalized.lifecycleState());
    }

    @Test
    void transition_on_unknown_node_returns_not_found() {
        RegistryException ex = assertThrows(RegistryException.class,
                () -> service.transition("ghost",
                        new TransitionRequest("INACTIVE", 1L, "req-ghost")));
        assertEquals(RegistryErrorCode.NODE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void repeated_transition_with_same_requestId_returns_stored_result_without_extra_event() {
        registerNode("node-1", "req-reg");
        TransitionRequest req = new TransitionRequest("INACTIVE", 1L, "req-cfg");
        NodeView first = service.transition("node-1", req);
        NodeView replay = service.transition("node-1", req);

        assertEquals(first, replay);
        Integer transitions = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM resource_event WHERE event_type = 'LIFECYCLE_TRANSITION'",
                Integer.class);
        assertEquals(1, transitions, "重复状态请求不得产生重复事件");
    }
}
