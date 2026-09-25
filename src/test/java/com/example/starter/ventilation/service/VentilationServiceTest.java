package com.example.starter.ventilation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.ventilation.ConflictException;
import com.example.starter.ventilation.MutableClock;
import com.example.starter.ventilation.NotFoundException;
import com.example.starter.ventilation.TimeService;
import com.example.starter.ventilation.dto.ActivateRequest;
import com.example.starter.ventilation.dto.ArchiveRequest;
import com.example.starter.ventilation.dto.CommandView;
import com.example.starter.ventilation.dto.FeedbackRequest;
import com.example.starter.ventilation.dto.ModeView;
import com.example.starter.ventilation.dto.ResetRequest;

/**
 * 通风联动核心业务测试：全部基于 H2 内存库（MODE=MySQL）真实事务与唯一约束，
 * 不使用 mock 替代数据库边界。
 */
@SpringBootTest
class VentilationServiceTest {

    private static final Instant BASE = Instant.parse("2026-09-25T01:00:00Z");

    @Autowired
    private VentilationService service;
    @Autowired
    private TimeService timeService;
    @Autowired
    private JdbcTemplate jdbc;

    private MutableClock clock;

    @BeforeEach
    void cleanAndResetClock() {
        jdbc.update("DELETE FROM event_log");
        jdbc.update("DELETE FROM device_feedback");
        jdbc.update("DELETE FROM device_command");
        jdbc.update("DELETE FROM active_zone_lock");
        jdbc.update("DELETE FROM idem_request");
        jdbc.update("DELETE FROM fire_mode");
        clock = new MutableClock(BASE, TimeService.ZONE);
        timeService.setClock(clock);
    }

    private String rid() {
        return UUID.randomUUID().toString().substring(0, 12);
    }

    private ActivateRequest activateReq(String requestId, String zone) {
        return new ActivateRequest(requestId, zone, "TO_EAST", "PURGE_MAX");
    }

    private CommandView command(ModeView mode, String phase, String deviceCode) {
        return mode.commands().stream()
                .filter(c -> c.phase().equals(phase) && c.deviceCode().equals(deviceCode))
                .findFirst().orElseThrow();
    }

    private void ack(String modeRequestId, String phase, String deviceCode) {
        ModeView mode = service.getMode(modeRequestId);
        CommandView c = command(mode, phase, deviceCode);
        service.feedback(new FeedbackRequest("fb-" + rid(), c.commandUid(),
                deviceCode, true, null));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(1) FROM " + table, Long.class);
    }

    private List<String> eventTypes(String modeRequestId) {
        return service.listEvents(modeRequestId).stream()
                .map(e -> e.eventType()).toList();
    }

    // ---------------- 先阀后机顺序 ----------------

    @Test
    void activate_follows_damper_before_fan_sequence() {
        String reqId = rid();
        ModeView mode = service.activate(activateReq(reqId, "Z1"));

        assertThat(mode.status()).isEqualTo("ACTIVE");
        // 激活瞬间仅下发第 1 步风阀，后续风阀与风机均 PENDING 等待。
        assertThat(command(mode, "PURGE", "D-Z1-1").status()).isEqualTo("ISSUED");
        assertThat(command(mode, "PURGE", "D-SHARED").status()).isEqualTo("PENDING");
        assertThat(command(mode, "PURGE", "F-Z1-1").status()).isEqualTo("PENDING");

        ack(reqId, "PURGE", "D-Z1-1");
        assertThat(command(service.getMode(reqId), "PURGE", "D-SHARED").status())
                .isEqualTo("ISSUED");
        // 第二道风阀未到位前，风机绝不允许启动（顺序不可颠倒）。
        assertThat(command(service.getMode(reqId), "PURGE", "F-Z1-1").status())
                .isEqualTo("PENDING");

        ack(reqId, "PURGE", "D-SHARED");
        assertThat(command(service.getMode(reqId), "PURGE", "F-Z1-1").status())
                .isEqualTo("ISSUED");

        ack(reqId, "PURGE", "F-Z1-1");
        ModeView done = service.getMode(reqId);
        assertThat(done.commands().stream().filter(c -> c.phase().equals("PURGE")))
                .allMatch(c -> "IN_POSITION".equals(c.status()));
        assertThat(eventTypes(reqId)).contains("PURGE_SEQUENCE_COMPLETE");
    }

    @Test
    void activate_unknown_zone_returns_404() {
        assertThatThrownBy(() -> service.activate(activateReq(rid(), "Z9")))
                .isInstanceOf(NotFoundException.class);
    }

    // ---------------- 60 秒恰超时：重发一次，再超时转故障并升级告警 ----------------

    @Test
    void timeout_at_exactly_60s_resends_once_then_faults_and_escalates() {
        String reqId = rid();
        ModeView mode = service.activate(activateReq(reqId, "Z3"));
        CommandView damper = command(mode, "PURGE", "D-Z3-1");
        assertThat(damper.status()).isEqualTo("ISSUED");
        LocalDateTime firstIssued = damper.issuedAt();

        // 59 秒：未到超时点，不重发
        clock.advanceSeconds(59);
        assertThat(service.sweepTimeouts()).isEmpty();
        CommandView at59 = command(service.getMode(reqId), "PURGE", "D-Z3-1");
        assertThat(at59.attempt()).isEqualTo(1);
        assertThat(at59.issuedAt()).isEqualTo(firstIssued);

        // 恰满 60 秒：自动重发一次
        clock.advanceSeconds(1);
        assertThat(service.sweepTimeouts()).containsExactly(damper.commandUid());
        CommandView resent = command(service.getMode(reqId), "PURGE", "D-Z3-1");
        assertThat(resent.attempt()).isEqualTo(2);
        assertThat(resent.status()).isEqualTo("ISSUED");
        assertThat(eventTypes(reqId)).contains("COMMAND_RESEND");

        // 重发后再恰满 60 秒仍未反馈：转设备故障并升级告警；风机被联锁不启动。
        clock.advanceSeconds(60);
        assertThat(service.sweepTimeouts()).containsExactly(damper.commandUid());
        ModeView faulted = service.getMode(reqId);
        CommandView failed = command(faulted, "PURGE", "D-Z3-1");
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.blockedReason()).contains("设备故障");
        assertThat(command(faulted, "PURGE", "F-Z3-1").status()).isEqualTo("PENDING");
        List<String> types = eventTypes(reqId);
        assertThat(types).contains("DEVICE_FAULT", "ALARM_ESCALATED");

        // 故障后继续扫描不重复处理
        clock.advanceSeconds(120);
        assertThat(service.sweepTimeouts()).isEmpty();
    }

    // ---------------- 故障设备不阻塞其他分区 ----------------

    @Test
    void device_fault_in_one_zone_does_not_block_other_zone() {
        // 先让 Z3 风阀两次超时转故障
        String z3 = rid();
        service.activate(activateReq(z3, "Z3"));
        clock.advanceSeconds(60);
        service.sweepTimeouts();
        clock.advanceSeconds(60);
        service.sweepTimeouts();
        assertThat(command(service.getMode(z3), "PURGE", "D-Z3-1").status())
                .isEqualTo("FAILED");

        // Z3 故障后，其他分区仍可独立激活并正常联动
        String z1 = rid();
        ModeView zone1 = service.activate(activateReq(z1, "Z1"));
        assertThat(zone1.status()).isEqualTo("ACTIVE");
        ack(z1, "PURGE", "D-Z1-1");
        ack(z1, "PURGE", "D-SHARED");
        ack(z1, "PURGE", "F-Z1-1");
        ModeView z1Done = service.getMode(z1);
        assertThat(z1Done.commands().stream().filter(c -> c.phase().equals("PURGE")))
                .allMatch(c -> "IN_POSITION".equals(c.status()));
        // Z3 故障指令仍为 FAILED，风机保持联锁 PENDING，互不影响。
        assertThat(command(service.getMode(z3), "PURGE", "D-Z3-1").status())
                .isEqualTo("FAILED");
        assertThat(command(service.getMode(z3), "PURGE", "F-Z3-1").status())
                .isEqualTo("PENDING");
    }

    // ---------------- 并发激活唯一成功 ----------------

    @Test
    void concurrent_activation_of_same_zone_only_one_succeeds() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "concurrent-" + i + "-" + rid();
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    service.activate(activateReq(requestId, "Z3"));
                    return "OK:" + requestId;
                } catch (ConflictException e) {
                    return "CONFLICT";
                }
            }));
        }
        start.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> f : futures) {
            results.add(f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        long success = results.stream().filter(r -> r.startsWith("OK:")).count();
        assertThat(success).isEqualTo(1);
        assertThat(results).filteredOn(r -> r.equals("CONFLICT")).hasSize(threads - 1);

        // 唯一锁、唯一激活模式；失败方未产生任何指令或额外模式。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM active_zone_lock WHERE zone_code = 'Z3'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM fire_mode WHERE zone_code = 'Z3' AND status = 'ACTIVE'",
                Long.class)).isEqualTo(1L);
        long commandCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM device_command dc JOIN fire_mode fm ON fm.id = dc.mode_id "
                        + "WHERE fm.zone_code = 'Z3'", Long.class);
        assertThat(commandCount).isEqualTo(2L); // Z3 预案：1 风阀 + 1 风机
    }

    // ---------------- 共享风阀冲突 ----------------

    @Test
    void shared_damper_follows_first_activated_zone() {
        String z1 = rid();
        service.activate(activateReq(z1, "Z1"));
        String z2 = rid();
        ModeView zone2 = service.activate(activateReq(z2, "Z2"));

        CommandView sharedByZ2 = command(zone2, "PURGE", "D-SHARED");
        assertThat(sharedByZ2.status()).isEqualTo("SKIPPED");
        assertThat(sharedByZ2.blockedReason()).contains("D-SHARED", "先激活分区");
        assertThat(zone2.conflictDetail()).contains("D-SHARED");
        assertThat(eventTypes(z2)).contains("SHARED_DAMPER_CONFLICT");

        // 先激活分区 Z1 对共享风阀的指令不受影响，仍可正常到位。
        ModeView zone1 = service.getMode(z1);
        assertThat(command(zone1, "PURGE", "D-SHARED").status())
                .isIn("PENDING", "ISSUED");
    }

    @Test
    void independent_adjacent_zones_activate_in_parallel_when_no_shared_device() {
        // Z1 与 Z3 不共享设备（Z3 独立风道），可各自激活。
        String z1 = rid();
        String z3 = rid();
        ModeView a = service.activate(activateReq(z1, "Z1"));
        ModeView b = service.activate(activateReq(z3, "Z3"));
        assertThat(a.status()).isEqualTo("ACTIVE");
        assertThat(b.status()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM active_zone_lock", Long.class))
                .isEqualTo(2L);
    }

    // ---------------- 逆序复位与激活互斥 ----------------

    @Test
    void reset_runs_reverse_order_and_blocks_reactivation_until_complete() {
        String reqId = rid();
        service.activate(activateReq(reqId, "Z3"));
        ack(reqId, "PURGE", "D-Z3-1");
        ack(reqId, "PURGE", "F-Z3-1");

        String resetId = rid();
        ModeView resetting = service.reset(new ResetRequest(resetId, reqId));
        assertThat(resetting.status()).isEqualTo("RESETTING");

        // 逆序：先停风机，风阀复位仍 PENDING 等待风机停稳到位
        CommandView fanStop = command(resetting, "RESET", "F-Z3-1");
        assertThat(fanStop.targetState()).isEqualTo("STOPPED");
        assertThat(fanStop.status()).isEqualTo("ISSUED");
        CommandView damperClose = command(resetting, "RESET", "D-Z3-1");
        assertThat(damperClose.targetState()).isEqualTo("CLOSED");
        assertThat(damperClose.status()).isEqualTo("PENDING");

        // 复位完成前该分区不接受新的激活指令
        assertThatThrownBy(() -> service.activate(activateReq(rid(), "Z3")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("并发激活仅一个成功");

        ack(reqId, "RESET", "F-Z3-1");
        assertThat(command(service.getMode(reqId), "RESET", "D-Z3-1").status())
                .isEqualTo("ISSUED");
        ack(reqId, "RESET", "D-Z3-1");

        ModeView completed = service.getMode(reqId);
        assertThat(completed.status()).isEqualTo("RESET_COMPLETE");
        assertThat(completed.resetCompletedAt()).isNotNull();
        // 复位完成释放锁，该分区可再次激活
        String reactivate = rid();
        ModeView again = service.activate(activateReq(reactivate, "Z3"));
        assertThat(again.status()).isEqualTo("ACTIVE");
    }

    @Test
    void cannot_reset_while_purge_feedback_still_pending() {
        String reqId = rid();
        service.activate(activateReq(reqId, "Z3"));
        // 风阀指令已下发尚未到位
        assertThatThrownBy(() -> service.reset(new ResetRequest(rid(), reqId)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("等待到位反馈");
    }

    // ---------------- 归档终态 ----------------

    @Test
    void archive_is_terminal_and_late_feedback_only_recorded() {
        String reqId = rid();
        service.activate(activateReq(reqId, "Z3"));
        ack(reqId, "PURGE", "D-Z3-1");
        ack(reqId, "PURGE", "F-Z3-1");
        service.reset(new ResetRequest(rid(), reqId));
        ack(reqId, "RESET", "F-Z3-1");
        ack(reqId, "RESET", "D-Z3-1");

        String archiveId = rid();
        ModeView archived = service.archive(new ArchiveRequest(archiveId, reqId));
        assertThat(archived.status()).isEqualTo("ARCHIVED");
        assertThat(archived.archivedAt()).isNotNull();

        // 重复归档：不同归档 requestId 也返回幂等成功（模式终态结论不变）
        ModeView replayed = service.archive(new ArchiveRequest(rid(), reqId));
        assertThat(replayed.status()).isEqualTo("ARCHIVED");
        assertThat(replayed.archivedAt()).isEqualTo(archived.archivedAt());

        // 归档后迟到反馈：只记迟到事件，不改变归档结论
        String damperUid = command(archived, "PURGE", "D-Z3-1").commandUid();
        var lateResult = service.feedback(new FeedbackRequest(rid(), damperUid,
                "D-Z3-1", true, "OPEN"));
        assertThat(lateResult.late()).isTrue();
        assertThat(lateResult.accepted()).isFalse();
        assertThat(service.getMode(reqId).status()).isEqualTo("ARCHIVED");
        assertThat(eventTypes(reqId)).contains("LATE_FEEDBACK");
        // 该指令历史反馈共 2 条：正常到位 1 条 + 归档后迟到 1 条，仅迟到条带 late_flag。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM device_feedback WHERE command_uid = ?",
                Long.class, damperUid)).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM device_feedback WHERE command_uid = ? AND late_flag = 1",
                Long.class, damperUid)).isEqualTo(1L);
    }

    @Test
    void cannot_archive_before_reset_complete() {
        String reqId = rid();
        service.activate(activateReq(reqId, "Z3"));
        assertThatThrownBy(() -> service.archive(new ArchiveRequest(rid(), reqId)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("尚未完成复位");
    }

    // ---------------- 幂等：同键同参重放、同键异参 409 ----------------

    @Test
    void activate_same_request_same_params_returns_first_result_without_new_commands() {
        String reqId = rid();
        ActivateRequest req = activateReq(reqId, "Z3");
        ModeView first = service.activate(req);
        long commandsAfterFirst = count("device_command");

        ModeView replay = service.activate(new ActivateRequest(reqId, "Z3", "TO_EAST",
                "PURGE_MAX"));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(count("device_command")).isEqualTo(commandsAfterFirst);
        assertThat(count("fire_mode")).isEqualTo(1L);
    }

    @Test
    void activate_same_request_different_params_conflicts_and_creates_nothing() {
        String reqId = rid();
        service.activate(activateReq(reqId, "Z3"));
        long modes = count("fire_mode");
        long commands = count("device_command");

        assertThatThrownBy(() -> service.activate(
                new ActivateRequest(reqId, "Z1", "TO_EAST", "PURGE_MAX")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("同键异参");
        assertThat(count("fire_mode")).isEqualTo(modes);
        assertThat(count("device_command")).isEqualTo(commands);
    }

    @Test
    void feedback_same_request_replays_first_result_without_double_apply() {
        String reqId = rid();
        ModeView mode = service.activate(activateReq(reqId, "Z3"));
        String uid = command(mode, "PURGE", "D-Z3-1").commandUid();
        String fbId = rid();
        var first = service.feedback(new FeedbackRequest(fbId, uid, "D-Z3-1", true, null));
        assertThat(first.accepted()).isTrue();

        var replay = service.feedback(new FeedbackRequest(fbId, uid, "D-Z3-1", true, null));
        assertThat(replay.status()).isEqualTo(first.status());
        // 到位只确认一次：后续指令只被推进一轮（风机仍为单条 ISSUED）。
        assertThat(command(service.getMode(reqId), "PURGE", "F-Z3-1").status())
                .isEqualTo("ISSUED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM device_feedback WHERE command_uid = ?",
                Long.class, uid)).isEqualTo(1L);
    }

    @Test
    void reset_unknown_mode_returns_404() {
        assertThatThrownBy(() -> service.reset(new ResetRequest(rid(), "no-such-mode")))
                .isInstanceOf(NotFoundException.class);
    }

    // ---------------- 故障分区火警解除后仍可复位归档 ----------------

    @Test
    void faulty_zone_can_reset_skipping_faulty_device_then_archive() {
        String reqId = rid();
        service.activate(activateReq(reqId, "Z3"));
        // 风阀两次超时故障，风机被联锁未启动
        clock.advanceSeconds(60);
        service.sweepTimeouts();
        clock.advanceSeconds(60);
        service.sweepTimeouts();
        assertThat(command(service.getMode(reqId), "PURGE", "D-Z3-1").status())
                .isEqualTo("FAILED");

        // 火警解除：故障风阀与未启动风机的复位指令均跳过，模式仍可完成复位
        ModeView resetting = service.reset(new ResetRequest(rid(), reqId));
        assertThat(resetting.status()).isEqualTo("RESET_COMPLETE");
        assertThat(command(resetting, "RESET", "D-Z3-1").status()).isEqualTo("SKIPPED");
        assertThat(command(resetting, "RESET", "F-Z3-1").status()).isEqualTo("SKIPPED");
        // 复位完成释放锁，分区可重新激活
        ModeView reactivated = service.activate(activateReq(rid(), "Z3"));
        assertThat(reactivated.status()).isEqualTo("ACTIVE");
    }

    // ---------------- 并发同键幂等 ----------------

    @Test
    void reset_same_request_replays_without_duplicate_reset_commands() {
        String reqId = rid();
        service.activate(activateReq(reqId, "Z3"));
        ack(reqId, "PURGE", "D-Z3-1");
        ack(reqId, "PURGE", "F-Z3-1");

        String resetId = rid();
        ModeView first = service.reset(new ResetRequest(resetId, reqId));
        long resetCommands = count("device_command") - 2L; // 减去两条 PURGE 指令
        assertThat(resetCommands).isEqualTo(2L);

        // 同键同参重放：返回同一复位中状态，不重复生成复位指令
        ModeView replay = service.reset(new ResetRequest(resetId, reqId));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.status()).isEqualTo("RESETTING");
        assertThat(count("device_command")).isEqualTo(4L);

        // 同键异参：409
        assertThatThrownBy(() -> service.reset(new ResetRequest(resetId, "other-mode")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("同键异参");
    }

    @Test
    void concurrent_same_request_id_collapses_to_single_mode_and_replays() throws Exception {
        int threads = 8;
        String sameRequestId = "same-key-" + rid();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return service.activate(activateReq(sameRequestId, "Z3")).id();
                } catch (ConflictException e) {
                    return -1L;
                }
            }));
        }
        start.countDown();
        List<Long> ids = new ArrayList<>();
        for (Future<Long> f : futures) {
            ids.add(f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        // 同 requestId 唯一约束：最终仅一个模式、一套指令；竞争方 409 后重放得到同一结果。
        assertThat(count("fire_mode")).isEqualTo(1L);
        Long winner = service.getMode(sameRequestId).id();
        assertThat(ids).contains(winner);
        ModeView replay = service.activate(activateReq(sameRequestId, "Z3"));
        assertThat(replay.id()).isEqualTo(winner);
        assertThat(count("device_command")).isEqualTo(2L);
    }
}
