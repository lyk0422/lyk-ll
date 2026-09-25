package com.example.starter.ventilation;

import com.example.starter.ventilation.service.ApiException;
import com.example.starter.ventilation.service.VentilationModeService;
import com.example.starter.ventilation.web.CommandView;
import com.example.starter.ventilation.web.FeedbackResponse;
import com.example.starter.ventilation.web.ModeResponse;
import com.example.starter.ventilation.web.SessionView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖题干要求：先阀后机顺序、60 秒恰超时重发、故障升级不阻塞他区、并发唯一激活、
 * 共享风阀冲突、逆序复位与激活互斥、归档终态、同键重放。
 */
class VentilationModeFlowTest extends AbstractVentilationIntegrationTest {

    private static final long T0 = 1_700_000_000_000L;

    @jakarta.annotation.Resource
    private VentilationModeService service;

    private void fixTime(long millis) {
        clock.setClock(Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC));
    }

    private void advance(long millis) {
        long current = clock.nowMillis();
        fixTime(current + millis);
    }

    private Map<String, CommandView> commandMap(SessionView s, String phase) {
        return s.commands().stream()
                .filter(c -> c.phase().equals(phase))
                .collect(Collectors.toMap(CommandView::deviceCode, c -> c));
    }

    private List<String> eventTypes(long sessionId) {
        return jdbc.queryForList(
                "SELECT event_type FROM event_log WHERE session_id = ? ORDER BY id ASC",
                String.class, sessionId);
    }

    private void ack(long sessionId, String deviceCode, String requestId) {
        FeedbackResponse r = service.feedback(requestId, sessionId, deviceCode, "IN_POSITION");
        assertTrue(r.accepted(), () -> "反馈应被采纳：" + r.note());
    }

    // ---------------------------------------------------------------
    // 1. 先阀后机顺序
    // ---------------------------------------------------------------
    @Test
    @DisplayName("激活先开风阀：风阀到位前风机保持 PENDING，全部风阀到位后才启动风机")
    void valveBeforeFanOrder() {
        fixTime(T0);
        ModeResponse activated = service.activate("req-act-1", "ZONE_A", "FORWARD", "SMOKE_EXHAUST");
        long sid = activated.session().sessionId();
        assertEquals("ACTIVATED", activated.outcome());

        Map<String, CommandView> cmds = commandMap(activated.session(), "ACTIVATE");
        // 三台风阀全部 SENT，风机仍是 PENDING
        assertEquals("SENT", cmds.get("D_A_1").status());
        assertEquals("SENT", cmds.get("D_A_2").status());
        assertEquals("SENT", cmds.get("D_AB_SHARED").status());
        assertEquals("PENDING", cmds.get("FAN_A").status());
        // 顺序号：所有风阀小于风机
        assertTrue(cmds.get("D_A_1").seqNo() < cmds.get("FAN_A").seqNo());
        assertTrue(cmds.get("D_AB_SHARED").seqNo() < cmds.get("FAN_A").seqNo());

        // 只到位两台风阀，风机仍不可下发
        ack(sid, "D_A_1", "fb-d1");
        ack(sid, "D_A_2", "fb-d2");
        assertEquals("PENDING", service.getSession(sid).commands().stream()
                .filter(c -> c.deviceCode().equals("FAN_A")).findFirst().orElseThrow().status());

        // 最后一台共享风阀到位，风机才 SENT
        ack(sid, "D_AB_SHARED", "fb-dshared");
        assertEquals("SENT", service.getSession(sid).commands().stream()
                .filter(c -> c.deviceCode().equals("FAN_A")).findFirst().orElseThrow().status());

        // 事件顺序：三条 COMMAND_SENT 风阀在先，风机 COMMAND_SENT 必须在其反馈之后
        List<String> types = eventTypes(sid);
        long firstFanSent = jdbc.queryForObject(
                "SELECT MIN(event_time) FROM event_log WHERE session_id=? AND event_type='COMMAND_SENT' "
                        + "AND detail LIKE '%FAN_A%'", Long.class, sid);
        long sharedAck = jdbc.queryForObject(
                "SELECT event_time FROM event_log WHERE session_id=? AND event_type='FEEDBACK_RECEIVED' "
                        + "AND detail LIKE '%D_AB_SHARED%' ORDER BY id ASC LIMIT 1", Long.class, sid);
        assertNotNull(firstFanSent);
        assertTrue(firstFanSent >= sharedAck, "风机启动不得早于最后风阀到位");
        assertTrue(types.contains("MODE_ACTIVATED"));
    }

    // ---------------------------------------------------------------
    // 2. 60 秒恰超时：重发一次，二次超时故障 + 升级告警
    // ---------------------------------------------------------------
    @Test
    @DisplayName("到位反馈恰满 60 秒判超时并重发一次，第二次仍满 60 秒转设备故障并升级告警")
    void timeoutAtExactly60SecondsResendThenFault() {
        fixTime(T0);
        ModeResponse activated = service.activate("req-timeout", "ZONE_C", "FORWARD", "SMOKE_EXHAUST");
        long sid = activated.session().sessionId();
        CommandView damper = commandMap(activated.session(), "ACTIVATE").get("D_C_1");
        long cid = damper.commandId();

        // 59.999 秒：尚未到恰满边界，不重发
        advance(59_999);
        assertEquals(0, service.scanTimeouts());
        assertEquals(1, jdbc.queryForObject(
                "SELECT attempt FROM device_command WHERE id=?", Integer.class, cid));

        // 恰满 60 秒：自动重发一次
        advance(1);
        assertEquals(1, service.scanTimeouts());
        assertEquals(2, jdbc.queryForObject(
                "SELECT attempt FROM device_command WHERE id=?", Integer.class, cid));
        assertEquals("SENT", jdbc.queryForObject(
                "SELECT status FROM device_command WHERE id=?", String.class, cid));
        assertTrue(eventTypes(sid).contains("COMMAND_RESENT"));

        // 再到恰满第二个 60 秒：转 FAULT 并升级告警
        advance(59_999);
        assertEquals(0, service.scanTimeouts());
        advance(1);
        assertEquals(1, service.scanTimeouts());
        assertEquals("FAULT", jdbc.queryForObject(
                "SELECT status FROM device_command WHERE id=?", String.class, cid));
        List<String> types = eventTypes(sid);
        assertTrue(types.contains("DEVICE_FAULT"));
        assertTrue(types.contains("ALARM_ESCALATED"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT fault_flag FROM mode_session WHERE id=?", Integer.class, sid));
    }

    // ---------------------------------------------------------------
    // 3. 故障设备不阻塞其他分区
    // ---------------------------------------------------------------
    @Test
    @DisplayName("A区设备故障升级不阻塞B区独立激活与指令推进")
    void faultDoesNotBlockOtherZone() {
        fixTime(T0);
        // 为避免共享风阀相互影响，A 用 BACKWARD（共享阀 CLOSE），B 用 FORWARD（共享阀期望 CLOSE→同向）
        ModeResponse a = service.activate("req-fault-a", "ZONE_A", "BACKWARD", "SMOKE_EXHAUST");
        long sidA = a.session().sessionId();
        // A 区风阀全部超时两轮 → 故障
        advance(60_000);
        service.scanTimeouts();
        advance(60_000);
        service.scanTimeouts();
        long faultCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM device_command WHERE session_id=? AND status='FAULT'",
                Long.class, sidA);
        assertTrue(faultCount >= 3);

        // B 区仍可独立激活，风阀正常下发
        ModeResponse b = service.activate("req-fault-b", "ZONE_B", "FORWARD", "SMOKE_EXHAUST");
        long sidB = b.session().sessionId();
        Map<String, CommandView> bCmds = commandMap(b.session(), "ACTIVATE");
        assertEquals("SENT", bCmds.get("D_B_1").status());
        // B 对共享阀 MINUS+FORWARD=CLOSE，与 A 先占用的 CLOSE 同向 → ALIGNED 而非被阻塞
        assertEquals("ALIGNED", bCmds.get("D_AB_SHARED").status());

        ack(sidB, "D_B_1", "fb-b-d1");
        // 共享阀 ALIGNED 视为已满足，风机照常启动
        assertEquals("SENT", service.getSession(sidB).commands().stream()
                .filter(c -> c.deviceCode().equals("FAN_B")).findFirst().orElseThrow().status());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(1) FROM mode_session WHERE zone_code='ZONE_B' AND status='ACTIVE'",
                Long.class));
    }

    // ---------------------------------------------------------------
    // 3b. 同区风阀故障则风机顺序不可颠倒（故障升级但本分区后续波次不推进）
    // ---------------------------------------------------------------
    @Test
    @DisplayName("同区一台风阀两轮超时故障后，风机不得启动（顺序不可颠倒），会话带故障标记")
    void zoneFaultBlocksFanButEscalatesAlarm() {
        fixTime(T0);
        ModeResponse activated = service.activate("req-zone-fault", "ZONE_A", "FORWARD",
                "SMOKE_EXHAUST");
        long sid = activated.session().sessionId();

        // 两台风阀正常到位，共享风阀两轮超时 → 故障
        ack(sid, "D_A_1", "zf-1");
        ack(sid, "D_A_2", "zf-2");
        advance(60_000);
        service.scanTimeouts();
        advance(60_000);
        service.scanTimeouts();

        assertEquals("FAULT", jdbc.queryForObject(
                "SELECT status FROM device_command WHERE session_id=? AND device_code='D_AB_SHARED'",
                String.class, sid));
        // 顺序不可颠倒：前序风阀未真正到位，风机保持 PENDING，未下发
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM device_command WHERE session_id=? AND device_code='FAN_A'",
                String.class, sid));
        assertEquals(1, jdbc.queryForObject(
                "SELECT fault_flag FROM mode_session WHERE id=?", Integer.class, sid));
        List<String> types = eventTypes(sid);
        assertTrue(types.contains("DEVICE_FAULT"));
        assertTrue(types.contains("ALARM_ESCALATED"));
        // 故障分区仍占锁，新激活被拒（人工处置前不允许覆盖）
        ModeResponse busy = service.activate("req-zone-fault-new", "ZONE_A", "BACKWARD",
                "SMOKE_EXHAUST");
        assertEquals("ZONE_BUSY", busy.outcome());
    }

    // ---------------------------------------------------------------
    // 4. 并发唯一激活
    // ---------------------------------------------------------------
    @Test
    @DisplayName("同一分区并发激活只有一个成功，其余得到 ZONE_BUSY 冲突说明")
    void concurrentActivationOnlyOneSucceeds() throws Exception {
        fixTime(T0);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<ModeResponse>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return service.activate("req-concurrent-" + idx, "ZONE_C",
                        "FORWARD", "SMOKE_EXHAUST");
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();

        long activated = 0;
        long busy = 0;
        Long winnerSession = null;
        for (var f : futures) {
            ModeResponse r = f.get(10, TimeUnit.SECONDS);
            if ("ACTIVATED".equals(r.outcome())) {
                activated++;
                winnerSession = r.session().sessionId();
            } else if ("ZONE_BUSY".equals(r.outcome())) {
                busy++;
                assertTrue(r.conflictReason().contains("ZONE_C"));
            }
        }
        pool.shutdown();
        assertEquals(1, activated, "恰好一个激活成功");
        assertEquals(threads - 1, busy, "其余全部得到分区占用冲突");
        assertNotNull(winnerSession);
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM mode_session WHERE zone_code='ZONE_C'", Long.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM zone_activation_lock WHERE zone_code='ZONE_C'", Long.class));
    }

    // ---------------------------------------------------------------
    // 5. 共享风阀冲突：以后激活分区得到冲突说明，先激活状态为准
    // ---------------------------------------------------------------
    @Test
    @DisplayName("共享风阀目标状态冲突时以先激活分区为准，后激活分区得到 CONFLICT 指令与说明")
    void sharedDamperConflict() {
        fixTime(T0);
        ModeResponse a = service.activate("req-shared-a", "ZONE_A", "FORWARD", "SMOKE_EXHAUST");
        // A 先激活：PLUS+FORWARD=OPEN
        assertEquals("SENT", commandMap(a.session(), "ACTIVATE").get("D_AB_SHARED").status());

        // B 同向烟流 FORWARD：MINUS+FORWARD=CLOSE，与先占用的 OPEN 冲突
        ModeResponse b = service.activate("req-shared-b", "ZONE_B", "FORWARD", "SMOKE_EXHAUST");
        assertEquals("ACTIVATED_WITH_SHARED_DAMPER_CONFLICT", b.outcome());
        assertNotNull(b.conflictReason());
        assertTrue(b.conflictReason().contains("D_AB_SHARED"));
        assertTrue(b.conflictReason().contains("ZONE_A"));
        assertEquals("CONFLICT", commandMap(b.session(), "ACTIVATE").get("D_AB_SHARED").status());

        // 占用表保持先激活分区的 OPEN
        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT target_action FROM shared_damper_commitment WHERE damper_code='D_AB_SHARED'",
                String.class));
        // B 的自有风阀不受冲突影响，正常推进到风机
        long sidB = b.session().sessionId();
        ack(sidB, "D_B_1", "fb-b-d1");
        assertEquals("SENT", service.getSession(sidB).commands().stream()
                .filter(c -> c.deviceCode().equals("FAN_B")).findFirst().orElseThrow().status());
    }

    @Test
    @DisplayName("相邻分区不冲突占用时可各自独立并行激活（共享阀同向只记 ALIGNED）")
    void adjacentZonesActivateIndependently() {
        fixTime(T0);
        ModeResponse a = service.activate("req-ind-a", "ZONE_A", "FORWARD", "SMOKE_EXHAUST");
        ModeResponse b = service.activate("req-ind-b", "ZONE_B", "BACKWARD", "PRESSURIZATION");
        assertEquals("ACTIVATED", a.outcome());
        assertEquals("ACTIVATED", b.outcome());
        // A: PLUS+FORWARD=OPEN；B: MINUS+BACKWARD=OPEN，同向
        assertEquals("ALIGNED", commandMap(b.session(), "ACTIVATE").get("D_AB_SHARED").status());
        assertEquals("SENT", commandMap(a.session(), "ACTIVATE").get("D_AB_SHARED").status());
        assertNotSame(a.session().sessionId(), b.session().sessionId());
    }

    // ---------------------------------------------------------------
    // 6. 逆序复位 + 复位期间激活互斥
    // ---------------------------------------------------------------
    @Test
    @DisplayName("复位按逆序先停风机后关风阀；复位完成前新激活被拒，完成后可再激活")
    void reverseResetAndActivationMutex() {
        fixTime(T0);
        ModeResponse activated = service.activate("req-reset-a", "ZONE_A", "FORWARD", "SMOKE_EXHAUST");
        long sid = activated.session().sessionId();
        ack(sid, "D_A_1", "fb-1");
        ack(sid, "D_A_2", "fb-2");
        ack(sid, "D_AB_SHARED", "fb-3");
        ack(sid, "FAN_A", "fb-fan");

        // 复位：只有风机 STOP 被下发，风阀 CLOSE 全部 PENDING
        ModeResponse resetting = service.reset("req-reset-do", "ZONE_A");
        assertEquals("RESET_IN_PROGRESS", resetting.outcome());
        Map<String, CommandView> reset = commandMap(service.getSession(sid), "RESET");
        assertEquals("SENT", reset.get("FAN_A").status());
        assertEquals("PENDING", reset.get("D_A_1").status());
        assertEquals("PENDING", reset.get("D_A_2").status());
        assertEquals("PENDING", reset.get("D_AB_SHARED").status());
        // 逆序：风机 seq 最小
        assertTrue(reset.get("FAN_A").seqNo() < reset.get("D_A_1").seqNo());

        // 复位完成前新激活被拒
        ModeResponse rejected = service.activate("req-reset-new", "ZONE_A", "BACKWARD",
                "SMOKE_EXHAUST");
        assertEquals("ZONE_BUSY", rejected.outcome());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM mode_session WHERE zone_code='ZONE_A'", Long.class));

        // 风机停稳到位 → 风阀 CLOSE 才下发（推进时钟，使“停风机早于关风阀”的时间序可验证）
        advance(1000);
        ack(sid, "FAN_A", "fb-stop-fan");
        assertEquals("SENT", jdbc.queryForObject(
                "SELECT status FROM device_command WHERE session_id=? AND phase='RESET' "
                        + "AND device_code='D_A_1'", String.class, sid));

        ack(sid, "D_A_1", "fb-c1");
        ack(sid, "D_A_2", "fb-c2");
        // 还剩共享风阀未关，仍未完成
        assertEquals("RESETTING", jdbc.queryForObject(
                "SELECT status FROM mode_session WHERE id=?", String.class, sid));
        ack(sid, "D_AB_SHARED", "fb-c3");

        assertEquals("RESET_COMPLETE", jdbc.queryForObject(
                "SELECT status FROM mode_session WHERE id=?", String.class, sid));
        List<String> types = eventTypes(sid);
        long fanStopSent = jdbc.queryForObject(
                "SELECT MIN(event_time) FROM event_log WHERE session_id=? AND event_type='COMMAND_SENT' "
                        + "AND detail LIKE '%STOP%'", Long.class, sid);
        long firstClose = jdbc.queryForObject(
                "SELECT MIN(event_time) FROM event_log WHERE session_id=? AND event_type='COMMAND_SENT' "
                        + "AND detail LIKE '%CLOSE%'", Long.class, sid);
        assertTrue(fanStopSent < firstClose, "停风机必须早于关风阀");
        assertTrue(types.contains("RESET_COMPLETE"));
        // 锁已释放，允许新的激活
        ModeResponse again = service.activate("req-reset-again", "ZONE_A", "FORWARD",
                "SMOKE_EXHAUST");
        assertEquals("ACTIVATED", again.outcome());
    }

    // ---------------------------------------------------------------
    // 7. 归档终态 + 迟到反馈
    // ---------------------------------------------------------------
    @Test
    @DisplayName("归档为终态：重复归档幂等；归档后迟到反馈只记迟到事件，不改变归档结论")
    void archiveTerminalAndLateFeedback() {
        fixTime(T0);
        ModeResponse activated = service.activate("req-arch-a", "ZONE_C", "FORWARD", "SMOKE_EXHAUST");
        long sid = activated.session().sessionId();
        ack(sid, "D_C_1", "fb-d");
        ack(sid, "FAN_C", "fb-fan");
        service.reset("req-arch-reset", "ZONE_C");
        ack(sid, "FAN_C", "fb-rfan");
        ack(sid, "D_C_1", "fb-rd");

        // 未归档可重复“复位完成”幂等返回
        ModeResponse archived = service.archive("req-arch-do", "ZONE_C");
        assertEquals("ARCHIVED", archived.outcome());
        assertEquals("ARCHIVED", jdbc.queryForObject(
                "SELECT status FROM mode_session WHERE id=?", String.class, sid));

        // 重复归档：同键重放返回首次结果（replay=true，首次结论为 ARCHIVED）
        ModeResponse replayArchive = service.archive("req-arch-do", "ZONE_C");
        assertTrue(replayArchive.replay());
        assertEquals("ARCHIVED", replayArchive.outcome());
        // 新 requestId 再归档幂等成功，结论为已归档
        ModeResponse again = service.archive("req-arch-again", "ZONE_C");
        assertFalse(again.replay());
        assertEquals("ALREADY_ARCHIVED", again.outcome());

        // 归档后迟到的设备反馈：只记迟到事件
        FeedbackResponse late = service.feedback("fb-late", sid, "FAN_C", "IN_POSITION");
        assertFalse(late.accepted());
        assertTrue(late.late());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(1) FROM device_feedback WHERE session_id=? AND late_flag=1",
                Integer.class, sid));
        assertTrue(eventTypes(sid).contains("LATE_FEEDBACK"));
        // 归档结论不变
        assertEquals("ARCHIVED", jdbc.queryForObject(
                "SELECT status FROM mode_session WHERE id=?", String.class, sid));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM zone_activation_lock WHERE zone_code='ZONE_C'", Long.class));

        // 迟到反馈同键重放返回首次结果
        FeedbackResponse lateReplay = service.feedback("fb-late", sid, "FAN_C", "IN_POSITION");
        assertTrue(lateReplay.replay());
        assertTrue(lateReplay.late());
    }

    @Test
    @DisplayName("复位完成前不可归档")
    void archiveRejectedBeforeResetComplete() {
        fixTime(T0);
        service.activate("req-arch2-a", "ZONE_C", "FORWARD", "SMOKE_EXHAUST");
        ModeResponse r = service.archive("req-arch2-do", "ZONE_C");
        assertEquals("MODE_NOT_RESET_COMPLETE", r.outcome());
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM mode_session", String.class));
    }

    // ---------------------------------------------------------------
    // 8. requestId 幂等：同键同参重放 / 同键异参 409 / 不产生新指令
    // ---------------------------------------------------------------
    @Test
    @DisplayName("激活 requestId 同键同参返回首次结果，同键异参 409 且不产生新指令")
    void idempotentActivateReplayAndConflict() {
        fixTime(T0);
        ModeResponse first = service.activate("req-idem", "ZONE_A", "FORWARD", "SMOKE_EXHAUST");
        long sid = first.session().sessionId();

        ModeResponse replay = service.activate("req-idem", "ZONE_A", "FORWARD", "SMOKE_EXHAUST");
        assertTrue(replay.replay());
        assertEquals(sid, replay.session().sessionId());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM mode_session WHERE activate_request_id='req-idem'",
                Long.class));

        long commandsBefore = jdbc.queryForObject(
                "SELECT COUNT(1) FROM device_command", Long.class);
        ApiException ex = assertThrows409(() ->
                service.activate("req-idem", "ZONE_A", "BACKWARD", "SMOKE_EXHAUST"));
        assertEquals("IDEM_KEY_MISMATCH", ex.getCode());
        // 异参 409 不产生新指令、不新增会话
        assertEquals(commandsBefore, jdbc.queryForObject(
                "SELECT COUNT(1) FROM device_command", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(1) FROM mode_session", Long.class));

        // 反馈也幂等
        FeedbackResponse fb = service.feedback("fb-idem", sid, "D_A_1", "IN_POSITION");
        assertTrue(fb.accepted());
        FeedbackResponse fbReplay = service.feedback("fb-idem", sid, "D_A_1", "IN_POSITION");
        assertTrue(fbReplay.replay());
        ApiException fbEx = assertThrows409(() ->
                service.feedback("fb-idem", sid, "D_A_2", "IN_POSITION"));
        assertEquals("IDEM_KEY_MISMATCH", fbEx.getCode());
    }

    @Test
    @DisplayName("复位 requestId 同键重放首次结论")
    void idempotentResetReplay() {
        fixTime(T0);
        ModeResponse a = service.activate("req-ir-a", "ZONE_C", "FORWARD", "SMOKE_EXHAUST");
        long sid = a.session().sessionId();
        ack(sid, "D_C_1", "fb-d");
        ack(sid, "FAN_C", "fb-f");
        ModeResponse r1 = service.reset("req-ir-reset", "ZONE_C");
        ack(sid, "FAN_C", "fb-rf");
        ack(sid, "D_C_1", "fb-rd");
        ModeResponse r2 = service.reset("req-ir-reset", "ZONE_C");
        assertTrue(r2.replay());
        assertEquals(r1.outcome(), r2.outcome());
        // 重放不产生新的复位指令（每设备仍仅一条 RESET 指令）
        long resetCommands = jdbc.queryForObject(
                "SELECT COUNT(1) FROM device_command WHERE phase='RESET'", Long.class);
        long devices = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT device_code) FROM device_command WHERE phase='RESET'",
                Long.class);
        assertEquals(devices, resetCommands);
    }

    private ApiException assertThrows409(org.junit.jupiter.api.function.Executable op) {
        try {
            op.execute();
            throw new AssertionError("期望抛出 ApiException");
        } catch (ApiException e) {
            assertEquals(org.springframework.http.HttpStatus.CONFLICT, e.getStatus());
            return e;
        } catch (Throwable t) {
            throw new AssertionError("期望 ApiException，实际：" + t, t);
        }
    }

    private static void assertNotSame(long a, long b) {
        assertTrue(a != b);
    }
}
