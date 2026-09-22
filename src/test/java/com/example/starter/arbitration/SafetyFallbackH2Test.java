package com.example.starter.arbitration;

import com.example.starter.arbitration.api.AcquireLeaseRequest;
import com.example.starter.arbitration.api.CommandRequest;
import com.example.starter.arbitration.api.HeartbeatRequest;
import com.example.starter.arbitration.domain.SafetyEventPo;
import com.example.starter.arbitration.repo.ResourceRepository;
import com.example.starter.arbitration.repo.SafetyEventRepository;
import com.example.starter.arbitration.service.LeaseArbitrationService;
import com.example.starter.arbitration.service.CommandService;
import com.example.starter.arbitration.service.ResourceService;
import com.example.starter.arbitration.service.SafetyReaper;
import com.example.starter.arbitration.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全降级：租约到期、控制器失联、指令超时的一次性安全状态进入与重复检测去重。
 */
class SafetyFallbackH2Test extends AbstractIntegrationTest {

    @Autowired
    private LeaseArbitrationService leaseService;
    @Autowired
    private CommandService commandService;
    @Autowired
    private ResourceService resourceService;
    @Autowired
    private SafetyReaper reaper;
    @Autowired
    private SafetyEventRepository safetyEventRepository;
    @Autowired
    private ResourceRepository resourceRepository;

    private void registerJoint(String name, String action, Double preset) {
        jdbc.update(
                "INSERT INTO t_resource (resource_name, min_position, max_position, "
                        + "max_velocity, max_acceleration, safety_action, preset_position) "
                        + "VALUES (?, -3.14, 3.14, 2.0, 5.0, ?, ?)",
                name, action, preset);
    }

    @Test
    void leaseExpiryEntersSafetyStateExactlyOnce() {
        registerJoint("joint_hold", "HOLD", null);
        leaseService.acquire(new AcquireLeaseRequest(
                "ctrl_a", "req-1", 1, List.of("joint_hold"), 500L));

        clock.advance(600);
        reaper.reapOnce();

        List<SafetyEventPo> events = safetyEventRepository.findAll();
        assertEquals(1, events.size());
        assertEquals("LEASE_EXPIRED", events.get(0).getReason());
        assertEquals("HOLD", events.get(0).getAction());
        assertEquals("joint_hold", events.get(0).getResourceName());
        assertTrue(jdbc.queryForObject(
                "SELECT active FROM t_lease WHERE request_id = 'req-1'", Boolean.class) == Boolean.FALSE);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_active_claim", Integer.class));

        // 重复检测不得重复执行降级动作
        reaper.reapOnce();
        reaper.reapOnce();
        assertEquals(1, safetyEventRepository.findAll().size());
    }

    @Test
    void leaseExpiryWithPresetPositionMovesResourceOnce() {
        registerJoint("joint_preset", "PRESET_POSITION", 0.75);
        leaseService.acquire(new AcquireLeaseRequest(
                "ctrl_a", "req-1", 1, List.of("joint_preset"), 500L));

        clock.advance(600);
        reaper.reapOnce();

        SafetyEventPo event = safetyEventRepository.findAll().get(0);
        assertEquals("PRESET_POSITION", event.getAction());
        assertEquals(0.75, event.getTargetPosition(), 1e-9);
        assertEquals(0.75,
                resourceRepository.findByName("joint_preset").orElseThrow().getLastPosition(), 1e-9,
                "模拟硬件应一次性进入预设位置");

        reaper.reapOnce();
        assertEquals(1, safetyEventRepository.findAll().size());
    }

    @Test
    void zeroVelocityActionRecordedOnLeaseExpiry() {
        registerJoint("joint_zero", "ZERO_VELOCITY", null);
        leaseService.acquire(new AcquireLeaseRequest(
                "ctrl_a", "req-1", 1, List.of("joint_zero"), 500L));

        clock.advance(600);
        reaper.reapOnce();

        assertEquals("ZERO_VELOCITY", safetyEventRepository.findAll().get(0).getAction());
    }

    @Test
    void controllerLostRevokesAllResourcesOncePerController() {
        registerJoint("joint_a", "HOLD", null);
        registerJoint("joint_b", "PRESET_POSITION", 0.2);
        // 长租约（远晚于心跳失联阈值才到期）
        leaseService.acquire(new AcquireLeaseRequest(
                "ctrl_a", "req-1", 1, List.of("joint_a", "joint_b"), 60_000L));

        clock.advance(900);
        reaper.reapOnce();

        List<SafetyEventPo> events = safetyEventRepository.findAll();
        assertEquals(2, events.size(), "失联控制器持有的每个资源都应进入安全状态");
        assertTrue(events.stream().allMatch(e -> "CONTROLLER_LOST".equals(e.getReason())));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_active_claim", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_lease WHERE active = FALSE AND revoked_reason = 'CONTROLLER_LOST'",
                Integer.class));
        assertTrue(jdbc.queryForObject(
                "SELECT lost FROM t_controller WHERE controller_id = 'ctrl_a'", Boolean.class));

        // 重复扫描不重复降级
        reaper.reapOnce();
        clock.advance(2_000);
        reaper.reapOnce();
        assertEquals(2, safetyEventRepository.findAll().size());
    }

    @Test
    void heartbeatRecoveryClearsLostState() {
        registerJoint("joint_a", "HOLD", null);
        leaseService.acquire(new AcquireLeaseRequest(
                "ctrl_a", "req-1", 1, List.of("joint_a"), 60_000L));
        clock.advance(900);
        reaper.reapOnce();
        assertTrue(jdbc.queryForObject(
                "SELECT lost FROM t_controller WHERE controller_id = 'ctrl_a'", Boolean.class));

        // 控制器恢复心跳后重新申请：失联标记清除
        resourceService.heartbeat(new HeartbeatRequest("ctrl_a"));
        assertFalse(jdbc.queryForObject(
                "SELECT lost FROM t_controller WHERE controller_id = 'ctrl_a'", Boolean.class));
        leaseService.acquire(new AcquireLeaseRequest(
                "ctrl_a", "req-2", 1, List.of("joint_a"), 60_000L));
        assertEquals("ctrl_a", jdbc.queryForObject(
                "SELECT controller_id FROM t_active_claim WHERE resource_name = 'joint_a'",
                String.class));
    }

    @Test
    void commandQueueTimeoutTriggersSafetyOnce() {
        registerJoint("joint_a", "ZERO_VELOCITY", null);
        leaseService.acquire(new AcquireLeaseRequest(
                "ctrl_a", "req-1", 1, List.of("joint_a"), 60_000L));
        commandService.submit(new CommandRequest(
                "ctrl_a", "cmd-1", "joint_a", clock.now() + 100, 1, 0.3, 1.0, 0.0));

        // 超过队列超时（测试配置 2000ms），期间保持心跳避免被判失联
        clock.advance(2_100);
        resourceService.heartbeat(new HeartbeatRequest("ctrl_a"));
        reaper.reapOnce();

        assertEquals("TIMED_OUT", jdbc.queryForObject(
                "SELECT status FROM t_command WHERE request_id = 'cmd-1'", String.class));
        List<SafetyEventPo> events = safetyEventRepository.findAll();
        assertEquals(1, events.size());
        assertEquals("COMMAND_TIMEOUT", events.get(0).getReason());
        assertEquals("ZERO_VELOCITY", events.get(0).getAction());

        reaper.reapOnce();
        assertEquals(1, safetyEventRepository.findAll().size(), "超时指令的降级不可重复执行");
    }

    @Test
    void dueCommandIsExecutedAndUpdatesLastTarget() {
        registerJoint("joint_a", "HOLD", null);
        leaseService.acquire(new AcquireLeaseRequest(
                "ctrl_a", "req-1", 1, List.of("joint_a"), 60_000L));
        commandService.submit(new CommandRequest(
                "ctrl_a", "cmd-1", "joint_a", clock.now() + 100, 1, 0.42, 1.0, 0.0));

        clock.advance(150);
        reaper.reapOnce();

        assertEquals("EXECUTED", jdbc.queryForObject(
                "SELECT status FROM t_command WHERE request_id = 'cmd-1'", String.class));
        assertEquals(0.42,
                resourceRepository.findByName("joint_a").orElseThrow().getLastPosition(), 1e-9);
        assertEquals(0, safetyEventRepository.findAll().size());
    }
}
