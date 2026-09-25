package com.example.starter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 救援主流程与失败分支：30 分钟超时升级、双人持证施救、90 日 2 次强制停梯、
 * 同键幂等重放、结案终态、复梯复检原子性。
 */
class RescueFlowTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");

    @Test
    @DisplayName("恰满30分钟未到场判定超时并升级通报；29分钟不超时")
    void arrivalTimeoutAtExactly30Minutes() throws Exception {
        long e1 = createElevator("T30-A", LocalDate.of(2026, 1, 1));
        long s1 = createStaff("C-T30-1", true);
        long s2 = createStaff("C-T30-2", true);

        long r1 = report("T30-r1", e1, T0).id();
        assertEquals(200, dispatch("T30-d1", r1, List.of(s1, s2)).status());
        Resp arrived = arrive(r1, T0.plusSeconds(30 * 60));
        assertEquals(200, arrived.status());
        assertTrue(arrived.bool("timeoutEscalated"), "恰满30分钟应判超时");
        assertNotNull(arrived.text("escalatedAt"), "应记录升级通报时间");
        assertTrue(timelineHas(r1, "ESCALATED"), "时间线应含升级通报事件");

        long e2 = createElevator("T30-B", LocalDate.of(2026, 1, 1));
        long r2 = report("T30-r2", e2, T0).id();
        assertEquals(200, dispatch("T30-d2", r2, List.of(s1, s2)).status());
        Resp arrived2 = arrive(r2, T0.plusSeconds(29 * 60));
        assertEquals(200, arrived2.status());
        assertFalse(arrived2.bool("timeoutEscalated"), "29分钟不应判超时");
        assertNull(arrived2.text("escalatedAt"));
    }

    @Test
    @DisplayName("施救须两名持证人员：单人或含未持证人员均不生效")
    void rescueRequiresTwoCertifiedStaff() throws Exception {
        long e1 = createElevator("T2P-A", LocalDate.of(2026, 1, 1));
        long s1 = createStaff("C-2P-1", true);
        long s2 = createStaff("C-2P-2", true);
        long s3 = createStaff("C-2P-3", false);

        long r1 = report("T2P-r1", e1, T0).id();
        assertEquals(200, dispatch("T2P-d1", r1, List.of(s1)).status());
        assertEquals(200, arrive(r1, T0.plusSeconds(600)).status());

        Resp single = rescue(r1, List.of(s1));
        assertEquals(422, single.status(), "单人施救不生效");
        assertEquals("TWO_RESCUERS_REQUIRED", single.text("error"));

        Resp uncertified = rescue(r1, List.of(s1, s3));
        assertEquals(422, uncertified.status(), "含未持证人员不生效");
        assertEquals("UNQUALIFIED_STAFF", uncertified.text("error"));

        Resp ok = rescue(r1, List.of(s1, s2));
        assertEquals(200, ok.status());
        assertEquals("RESCUED", ok.text("status"));
    }

    @Test
    @DisplayName("90日（UTC日界）内恰第2次困人强制停梯；第90日之外不触发")
    void secondIncidentWithin90DaysForcesStop() throws Exception {
        long s1 = createStaff("C-90-1", true);
        long s2 = createStaff("C-90-2", true);

        // 窗口内：第 1 次 2026-03-01，第 2 次 2026-05-29（相隔 89 个 UTC 日）
        long e1 = createElevator("T90-A", LocalDate.of(2026, 1, 1));
        fullLifecycle(e1, s1, s2, "T90-first", Instant.parse("2026-03-01T08:00:00Z"));
        assertEquals("RUNNING", elevator(e1).text("status"), "首单复检后应恢复运行");

        Resp second = report("T90-second", e1, Instant.parse("2026-05-29T08:00:00Z"));
        assertEquals(200, second.status());
        assertEquals("STOPPED", elevator(e1).text("status"));
        assertTrue(chainHas(e1, "STOP", "FORCE_STOP_90D"), "恰第2次困人应强制停梯检查");
        assertTrue(timelineHas(second.id(), "ESCALATED"));

        // 窗口外：第 2 次在 2026-05-30（相隔 90 个 UTC 日），不触发强制停梯
        long e2 = createElevator("T90-B", LocalDate.of(2026, 1, 1));
        fullLifecycle(e2, s1, s2, "T90-out1", Instant.parse("2026-03-01T08:00:00Z"));
        Resp outside = report("T90-out2", e2, Instant.parse("2026-05-30T08:00:00Z"));
        assertEquals(200, outside.status());
        assertFalse(chainHas(e2, "STOP", "FORCE_STOP_90D"), "超出90日窗口不应强制停梯");
    }

    @Test
    @DisplayName("同键同参返回首次结果；同键异参409且不改变电梯状态")
    void idempotentReplayAndConflict() throws Exception {
        long e1 = createElevator("TIDEM-A", LocalDate.of(2026, 1, 1));
        long s1 = createStaff("C-ID-1", true);
        long s2 = createStaff("C-ID-2", true);

        Resp first = report("IDEM-r1", e1, T0);
        assertEquals(200, first.status());
        Resp replay = report("IDEM-r1", e1, T0);
        assertEquals(200, replay.status(), "同键同参应返回首次结果");
        assertEquals(om.writeValueAsString(first.body()), om.writeValueAsString(replay.body()));

        Resp conflict = report("IDEM-r1", e1, T0.plusSeconds(60));
        assertEquals(409, conflict.status(), "同键异参应 409");
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.text("error"));
        Integer rescueCount = jdbc.queryForObject("SELECT COUNT(*) FROM rescue_order", Integer.class);
        assertEquals(1, rescueCount, "异参重放不得新增救援单");
        assertEquals("STOPPED", elevator(e1).text("status"), "异参重放不得改变电梯状态");

        long rescueId = first.id();
        Resp d1 = dispatch("IDEM-d1", rescueId, List.of(s1, s2));
        assertEquals(200, d1.status());
        Resp d1Replay = dispatch("IDEM-d1", rescueId, List.of(s1, s2));
        assertEquals(200, d1Replay.status());
        assertEquals(om.writeValueAsString(d1.body()), om.writeValueAsString(d1Replay.body()));
        Resp d1Conflict = dispatch("IDEM-d1", rescueId, List.of(s1));
        assertEquals(409, d1Conflict.status(), "派单同键异参应 409");
        assertEquals("DISPATCHED", getJson("/api/rescues/" + rescueId).text("status"), "异参重放不得改变状态");
    }

    @Test
    @DisplayName("结案为终态：重复结案幂等成功，结案后流程操作被拒绝，迟到维保只记事件")
    void closeIsTerminal() throws Exception {
        long e1 = createElevator("TCL-A", LocalDate.of(2026, 1, 1));
        long s1 = createStaff("C-CL-1", true);
        long s2 = createStaff("C-CL-2", true);
        long rescueId = fullLifecycle(e1, s1, s2, "TCL", T0);
        assertEquals("CLOSED", getJson("/api/rescues/" + rescueId).text("status"));

        Resp again = close("TCL-close", rescueId);
        assertEquals(200, again.status(), "同键重复结案返回幂等成功");
        assertEquals("CLOSED", again.text("status"));
        Resp otherKey = close("TCL-close-2", rescueId);
        assertEquals(200, otherKey.status(), "结案终态下异键重复结案亦幂等成功");
        assertEquals("CLOSED", otherKey.text("status"));

        assertEquals(409, arrive(rescueId, T0.plusSeconds(3600)).status(), "结案后到场应被拒绝");
        assertEquals(409, rescue(rescueId, List.of(s1, s2)).status(), "结案后施救应被拒绝");
        assertEquals(409, recheck(rescueId, true).status(), "结案后复检应被拒绝");

        Resp late = maintenance("TCL-m1", e1, LocalDate.of(2026, 3, 10), "TCL-report");
        assertEquals(200, late.status());
        assertEquals("LATE_EVENT", late.text("result"), "结案后迟到维保只记迟到事件");
        assertNull(elevator(e1).text("lastMaintenanceDay"), "迟到事件不得变更维保台账状态");
        assertEquals("RUNNING", elevator(e1).text("status"), "迟到事件不得改变电梯状态");
    }

    @Test
    @DisplayName("复梯与复检合格同事务：复检不合格不复梯；合格前不得先复梯")
    void recheckAndResumeAreAtomic() throws Exception {
        long e1 = createElevator("TRC-A", LocalDate.of(2026, 1, 1));
        long s1 = createStaff("C-RC-1", true);
        long s2 = createStaff("C-RC-2", true);

        long r1 = report("TRC-r1", e1, T0).id();
        assertEquals(200, dispatch("TRC-d1", r1, List.of(s1, s2)).status());
        assertEquals(200, arrive(r1, T0.plusSeconds(600)).status());
        assertEquals(200, rescue(r1, List.of(s1, s2)).status());

        assertEquals("STOPPED", elevator(e1).text("status"), "施救后复检前不得复梯");
        assertFalse(chainHas(e1, "RESUME", "RECHECK_PASS"), "复检合格前不得存在复梯链");

        Resp failed = recheck(r1, false);
        assertEquals(422, failed.status());
        assertEquals("INSPECTION_FAILED", failed.text("error"));
        assertEquals("STOPPED", elevator(e1).text("status"), "复检不合格禁止复梯");
        assertEquals("RESCUED", getJson("/api/rescues/" + r1).text("status"), "复检不合格不得推进状态");
        assertFalse(chainHas(e1, "RESUME", "RECHECK_PASS"));

        Resp ok = recheck(r1, true);
        assertEquals(200, ok.status());
        assertEquals("RESTORED", ok.text("status"));
        assertEquals("RUNNING", elevator(e1).text("status"), "复检合格与复梯应同事务生效");
        assertTrue(chainHas(e1, "RESUME", "RECHECK_PASS"));
    }

    @Test
    @DisplayName("同一电梯同时刻只允许一个进行中救援单")
    void singleActiveRescuePerElevator() throws Exception {
        long e1 = createElevator("TACT-A", LocalDate.of(2026, 1, 1));
        Resp first = report("TACT-r1", e1, T0);
        assertEquals(200, first.status());
        Resp second = report("TACT-r2", e1, T0.plusSeconds(60));
        assertEquals(409, second.status(), "进行中救援单未结案前不得再次接报");
        assertEquals("ACTIVE_RESCUE_EXISTS", second.text("error"));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rescue_order WHERE elevator_id=?", Integer.class, e1);
        assertEquals(1, count);
    }
}
