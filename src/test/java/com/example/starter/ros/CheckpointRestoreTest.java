package com.example.starter.ros;

import com.example.starter.ros.api.NodeView;
import com.example.starter.ros.api.RegisterNodeRequest;
import com.example.starter.ros.api.ResourceSpec;
import com.example.starter.ros.api.RestoreResult;
import com.example.starter.ros.api.TransitionRequest;
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
 * 检查点保存与当前 JVM 内恢复的 H2 集成测试。
 */
class CheckpointRestoreTest extends AbstractRegistryIntegrationTest {

    @Autowired
    private NodeRegistryService service;

    private NodeView register(String nodeId, String requestId, String svcName) {
        return service.register(new RegisterNodeRequest(nodeId, "/robot", "cp_node",
                List.of(
                        new ResourceSpec("SERVICE", svcName, "example/srv/Trigger"),
                        new ResourceSpec("TOPIC", "/robot/pose", "std_msgs/msg/Pose")),
                requestId));
    }

    @Test
    void restore_rebuilds_nodes_versions_resources_and_processed_request_ids() {
        NodeView node1 = register("node-1", "req-reg-1", "/robot/svc-1");
        service.transition("node-1",
                new TransitionRequest("INACTIVE", node1.version(), "req-cfg-1"));
        register("node-2", "req-reg-2", "/robot/svc-2");

        long revisionBefore = jdbcTemplate.queryForObject(
                "SELECT next_value FROM revision_seq WHERE id = 1", Long.class);

        service.saveCheckpoint("cp-1", "req-save");
        // 保存检查点之后继续产生变更（崩溃 node-2），用于证明恢复回到检查点时刻
        service.crash("node-2", "req-crash-2");

        RestoreResult result = service.restoreCheckpoint("cp-1", "req-restore");

        assertEquals(2, result.restoredNodes().size());
        assertTrue(result.restoredRequestIds().contains("req-reg-1"));
        assertTrue(result.restoredRequestIds().contains("req-reg-2"));
        assertTrue(result.restoredRequestIds().contains("req-cfg-1"));
        assertFalse(result.restoredRequestIds().contains("req-crash-2"),
                "检查点之后的 requestId 不应恢复");

        // node-1 恢复为检查点时刻的 INACTIVE / version=2
        NodeView restoredNode1 = service.getNode("node-1");
        assertEquals("INACTIVE", restoredNode1.lifecycleState());
        assertEquals(2L, restoredNode1.version());
        assertEquals(2, restoredNode1.resources().size());

        // node-2 恢复为存活（检查点时刻尚未崩溃）
        NodeView restoredNode2 = service.getNode("node-2");
        assertTrue(restoredNode2.alive());
        assertEquals(2, restoredNode2.resources().size());

        // revision 游标恢复到检查点时刻，且恢复本身未发布事件
        Long revisionAfter = jdbcTemplate.queryForObject(
                "SELECT next_value FROM revision_seq WHERE id = 1", Long.class);
        assertEquals(revisionBefore, revisionAfter, "恢复不得重放/新增事件");
        assertEquals(result.lastEventRevision(), revisionAfter);
    }

    @Test
    void restored_request_ids_remain_idempotent_after_restore() {
        register("node-1", "req-reg-1", "/robot/svc-1");
        service.saveCheckpoint("cp-1", "req-save");
        service.deregister("node-1", "req-teardown");
        assertEquals(0, service.listNodes().size());

        service.restoreCheckpoint("cp-1", "req-restore");

        // 恢复后使用已处理 requestId 同参重放：返回检查点时的原始注册结果，不重新注册
        NodeView replay = register("node-1", "req-reg-1", "/robot/svc-1");
        assertEquals("node-1", replay.nodeId());
        assertEquals(2, replay.resources().size());

        Integer nodeRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM ros_node", Integer.class);
        assertEquals(1, nodeRows);
    }

    @Test
    void restore_does_not_replay_confirmed_events_to_subscribers() {
        register("node-1", "req-reg-1", "/robot/svc-1");
        service.saveCheckpoint("cp-1", "req-save");

        long maxRevisionBefore = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(revision), 0) FROM resource_event", Long.class);

        service.restoreCheckpoint("cp-1", "req-restore");

        long maxRevisionAfter = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(revision), 0) FROM resource_event", Long.class);
        assertEquals(maxRevisionBefore, maxRevisionAfter, "恢复不得产生新 revision 的事件");

        // 事件流内容与检查点完全一致（revision 与类型）
        Integer declared = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM resource_event WHERE event_type = 'RESOURCE_DECLARED'",
                Integer.class);
        assertEquals(2, declared);
    }

    @Test
    void unknown_checkpoint_and_duplicate_checkpoint_are_rejected() {
        RegistryException missing = assertThrows(RegistryException.class,
                () -> service.restoreCheckpoint("nope", "req-restore"));
        assertEquals(RegistryErrorCode.CHECKPOINT_ERROR, missing.getErrorCode());

        register("node-1", "req-reg-1", "/robot/svc-1");
        service.saveCheckpoint("cp-1", "req-save");
        RegistryException duplicate = assertThrows(RegistryException.class,
                () -> service.saveCheckpoint("cp-1", "req-save-2"));
        assertEquals(RegistryErrorCode.CHECKPOINT_ERROR, duplicate.getErrorCode());
    }

    @Test
    void restore_request_itself_is_idempotent() {
        register("node-1", "req-reg-1", "/robot/svc-1");
        service.saveCheckpoint("cp-1", "req-save");

        RestoreResult first = service.restoreCheckpoint("cp-1", "req-restore");
        RestoreResult replay = service.restoreCheckpoint("cp-1", "req-restore");
        assertEquals(first, replay);

        // 第二次恢复是重放，不应再次清空重建并改变 revision
        Long revision = jdbcTemplate.queryForObject(
                "SELECT next_value FROM revision_seq WHERE id = 1", Long.class);
        assertEquals(first.lastEventRevision(), revision);
    }
}
