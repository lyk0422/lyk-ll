package com.example.starter.arbitration;

import com.example.starter.arbitration.api.AcquireLeaseRequest;
import com.example.starter.arbitration.api.CommandRequest;
import com.example.starter.arbitration.service.ApiException;
import com.example.starter.arbitration.service.CommandService;
import com.example.starter.arbitration.service.EstopService;
import com.example.starter.arbitration.service.LeaseArbitrationService;
import com.example.starter.arbitration.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 紧急停止：立即撤销全部写权限、清空待执行指令；显式恢复前拒绝申请与指令；操作幂等。
 */
class EstopH2Test extends AbstractIntegrationTest {

    @Autowired
    private EstopService estopService;
    @Autowired
    private LeaseArbitrationService leaseService;
    @Autowired
    private CommandService commandService;

    @BeforeEach
    void setUpTwoResources() {
        jdbc.update(
                "INSERT INTO t_resource (resource_name, min_position, max_position, "
                        + "max_velocity, max_acceleration, safety_action, preset_position) "
                        + "VALUES ('joint_a', -3.14, 3.14, 2.0, 5.0, 'HOLD', NULL)");
        jdbc.update(
                "INSERT INTO t_resource (resource_name, min_position, max_position, "
                        + "max_velocity, max_acceleration, safety_action, preset_position) "
                        + "VALUES ('joint_b', -3.14, 3.14, 2.0, 5.0, 'HOLD', NULL)");
    }

    @Test
    void estopRevokesAllWriteAccessAndClearsPendingCommands() {
        leaseService.acquire(new AcquireLeaseRequest(
                "ctrl_a", "req-a", 1, List.of("joint_a"), 60_000L));
        leaseService.acquire(new AcquireLeaseRequest(
                "ctrl_b", "req-b", 2, List.of("joint_b"), 60_000L));
        commandService.submit(new CommandRequest(
                "ctrl_a", "cmd-1", "joint_a", clock.now() + 100, 1, 0.2, 0.0, 0.0));

        estopService.trigger();

        assertTrue(estopService.isActive());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_active_claim", Integer.class),
                "急停必须立即撤销所有写权限");
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_lease WHERE active = FALSE AND revoked_reason = 'ESTOP'",
                Integer.class));
        assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(*) FROM t_command WHERE status = 'PENDING'",
                        Integer.class),
                "急停必须清空待执行指令");
        assertEquals("TIMED_OUT", jdbc.queryForObject(
                "SELECT status FROM t_command WHERE request_id = 'cmd-1'", String.class));

        // 显式恢复前拒绝普通申请与指令
        assertEquals(423, assertThrows(ApiException.class, () -> leaseService.acquire(
                new AcquireLeaseRequest("ctrl_c", "req-c", 1, List.of("joint_a"), 60_000L)))
                .getStatus().value());
        assertEquals(423, assertThrows(ApiException.class, () -> commandService.submit(new CommandRequest(
                "ctrl_a", "cmd-2", "joint_a", clock.now() + 100, 2, 0.2, 0.0, 0.0)))
                .getStatus().value());
    }

    @Test
    void estopTriggerIsIdempotentAndRecoverAllowsNewRequests() {
        leaseService.acquire(new AcquireLeaseRequest(
                "ctrl_a", "req-a", 1, List.of("joint_a"), 60_000L));
        estopService.trigger();
        // 重复触发不报错、不产生额外效果
        estopService.trigger();
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_lease WHERE revoked_reason = 'ESTOP'", Integer.class));

        // 未急停时恢复返回 409
        estopService.recover();
        assertFalse(estopService.isActive());
        ApiException ex = assertThrows(ApiException.class, estopService::recover);
        assertEquals("ESTOP_NOT_ACTIVE", ex.getCode());
        assertEquals(409, ex.getStatus().value());

        // 恢复后可重新申请
        assertTrue(leaseService.acquire(new AcquireLeaseRequest(
                "ctrl_a", "req-a2", 1, List.of("joint_a"), 60_000L)).granted());
    }

    @Test
    void afterEstopRecoverNewCommandsAreAcceptedAndExecuted() {
        leaseService.acquire(new AcquireLeaseRequest(
                "ctrl_a", "req-a", 1, List.of("joint_a"), 60_000L));
        estopService.trigger();
        estopService.recover();

        leaseService.acquire(new AcquireLeaseRequest(
                "ctrl_a", "req-a2", 1, List.of("joint_a"), 60_000L));
        var resp = commandService.submit(new CommandRequest(
                "ctrl_a", "cmd-new", "joint_a", clock.now() + 50, 1, 0.3, 0.0, 0.0));
        assertTrue(resp.accepted());
    }
}
