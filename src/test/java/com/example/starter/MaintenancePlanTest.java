package com.example.starter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 维保履约续派：扫描派发义务（到期前24小时，恰在起点允许，同梯同周期唯一）；
 * 两名持证人员 + 半开作业窗；救援挂起与续派；旧尝试只记迟到；
 * 逾期续派不自动复梯；手工入口结清义务；expectedVersion 乐观锁与 requestId 幂等。
 */
class MaintenancePlanTest extends AbstractIntegrationTest {

    private static final Instant OPEN_AT = Instant.parse("2026-01-15T00:00:00Z"); // due 2026-01-16 00:00 UTC 前24h
    private static final Instant W1S = Instant.parse("2026-01-15T08:00:00Z");
    private static final Instant W1E = Instant.parse("2026-01-15T12:00:00Z");

    @Test
    @DisplayName("扫描：恰在起点（到期前24小时）允许派发，之前不派，重复扫描不多派，同键重放冻结")
    void scanCreatesObligationAtWindowStart() throws Exception {
        long e1 = createElevator("MP-SCAN-A", LocalDate.of(2026, 1, 1));

        Resp early = scan("MPS-early", Instant.parse("2026-01-14T23:59:59Z"));
        assertEquals(200, early.status());
        assertEquals(0, early.body().get("created").asInt(), "起点前不得派发义务");

        Resp atStart = scan("MPS-start", OPEN_AT);
        assertEquals(200, atStart.status(), "恰在起点应允许派发");
        assertEquals(1, atStart.body().get("created").asInt());
        var ob = atStart.body().get("obligations").get(0);
        assertEquals("2026-01-01", ob.get("baseDay").asText(), "基准日为投运日");
        assertEquals("2026-01-16", ob.get("dueDay").asText(), "到期日=基准日+15日");
        assertEquals("2026-01-16T00:00:00Z", ob.get("dueAt").asText(), "固定UTC到期时刻");
        assertEquals("2026-01-15T00:00:00Z", ob.get("dispatchOpenAt").asText());
        assertEquals("OPEN", ob.get("status").asText());
        assertEquals(1, ob.get("version").asLong(), "版本从1开始");

        Resp again = scan("MPS-again", OPEN_AT.plusSeconds(3600));
        assertEquals(200, again.status());
        assertEquals(0, again.body().get("created").asInt(), "重复扫描不多派");
        assertEquals(1, obligations(e1).body().size(), "同梯同周期仅一项义务");

        Resp replay = scan("MPS-start", OPEN_AT);
        assertEquals(200, replay.status(), "同键同参重放首次冻结结果");
        assertEquals(om.writeValueAsString(atStart.body()), om.writeValueAsString(replay.body()));
        Resp conflict = scan("MPS-start", OPEN_AT.plusSeconds(1));
        assertEquals(409, conflict.status(), "同键异参应409");
    }

    @Test
    @DisplayName("派发：两名持证人员+合法半开窗；版本冲突409；非法窗400；未持证422；成功版本+1")
    void dispatchValidation() throws Exception {
        long e1 = createElevator("MP-DSP-A", LocalDate.of(2026, 1, 1));
        long s1 = createStaff("MP-D-1", true);
        long s2 = createStaff("MP-D-2", true);
        long s3 = createStaff("MP-D-3", false);
        long ob = scanOpenObligation("MPD", e1, OPEN_AT);

        Resp oneStaff = dispatchAttempt("MPD-bad1", ob, 1, List.of(s1), W1S, W1E);
        assertEquals(422, oneStaff.status(), "单人任务不生效");
        assertEquals("TWO_STAFF_REQUIRED", oneStaff.text("error"));

        Resp uncertified = dispatchAttempt("MPD-bad2", ob, 1, List.of(s1, s3), W1S, W1E);
        assertEquals(422, uncertified.status());
        assertEquals("UNQUALIFIED_STAFF", uncertified.text("error"));

        Resp ghost = dispatchAttempt("MPD-bad3", ob, 1, List.of(s1, 99999L), W1S, W1E);
        assertEquals(404, ghost.status(), "人员不存在应404");

        Resp badWindow = dispatchAttempt("MPD-bad4", ob, 1, List.of(s1, s2), W1E, W1S);
        assertEquals(400, badWindow.status(), "作业窗非法应400");
        assertEquals("INVALID_WINDOW", badWindow.text("error"));

        Resp badVersion = dispatchAttempt("MPD-bad5", ob, 99, List.of(s1, s2), W1S, W1E);
        assertEquals(409, badVersion.status(), "版本不符应409");
        assertEquals("VERSION_CONFLICT", badVersion.text("error"));

        assertEquals(0, obligationAttempts(ob).body().size(), "失败整批不占资源");

        Resp ok = dispatchAttempt("MPD-ok", ob, 1, List.of(s1, s2), W1S, W1E);
        assertEquals(200, ok.status());
        assertEquals("SCHEDULED", ok.text("status"));
        assertEquals(1, ok.body().get("version").asLong(), "尝试版本从1开始");
        assertEquals(2, obligation(ob).body().get("version").asLong(), "成功变更义务版本+1");

        Resp active = dispatchAttempt("MPD-ok2", ob, 2, List.of(s1, s2),
                W1E, W1E.plusSeconds(3600));
        assertEquals(422, active.status(), "存在未终结尝试不得重复派发");
        assertEquals("ACTIVE_ATTEMPT_EXISTS", active.text("error"));
    }

    @Test
    @DisplayName("人员半开窗不可重叠：重叠422且整批不占资源；相邻窗与不同人员允许")
    void staffWindowOverlap() throws Exception {
        long e1 = createElevator("MP-OVL-A", LocalDate.of(2026, 1, 1));
        long e2 = createElevator("MP-OVL-B", LocalDate.of(2026, 1, 1));
        long s1 = createStaff("MP-O-1", true);
        long s2 = createStaff("MP-O-2", true);
        long s3 = createStaff("MP-O-3", true);
        long s4 = createStaff("MP-O-4", true);
        long ob1 = scanOpenObligation("MPO-1", e1, OPEN_AT);
        long ob2 = scanOpenObligation("MPO-2", e2, OPEN_AT);

        assertEquals(200, dispatchAttempt("MPO-a", ob1, 1, List.of(s1, s2), W1S, W1E).status());

        Resp overlap = dispatchAttempt("MPO-b", ob2, 1, List.of(s2, s3),
                W1S.plusSeconds(3600), W1E.plusSeconds(3600));
        assertEquals(422, overlap.status(), "人员同窗重叠应拒绝");
        assertEquals("STAFF_WINDOW_OVERLAP", overlap.text("error"));
        assertEquals(0, obligationAttempts(ob2).body().size(), "任一不足整批不占资源");

        Resp adjacent = dispatchAttempt("MPO-c", ob2, 1, List.of(s2, s3),
                W1E, W1E.plusSeconds(7200));
        assertEquals(200, adjacent.status(), "半开窗相邻（终点=起点）不重叠");

        long e3 = createElevator("MP-OVL-C", LocalDate.of(2026, 1, 1));
        long ob3 = scanOpenObligation("MPO-3", e3, OPEN_AT);
        Resp otherStaff = dispatchAttempt("MPO-d", ob3, 1, List.of(s3, s4), W1S, W1E);
        assertEquals(200, otherStaff.status(), "不同人员同窗不冲突");
    }

    @Test
    @DisplayName("开始作业重查证书有效；挂起后不得再开始")
    void startRechecksCertificate() throws Exception {
        long e1 = createElevator("MP-START-A", LocalDate.of(2026, 1, 1));
        long s1 = createStaff("MP-S-1", true);
        long s2 = createStaff("MP-S-2", true);
        long ob = scanOpenObligation("MPST", e1, OPEN_AT);
        long at = dispatchAttempt("MPST-d", ob, 1, List.of(s1, s2), W1S, W1E).id();

        jdbc.update("UPDATE staff SET certified=FALSE WHERE id=?", s2);
        Resp denied = startAttempt("MPST-s1", at, 1, W1S);
        assertEquals(422, denied.status(), "证书失效应拒绝开始");
        assertEquals("UNQUALIFIED_STAFF", denied.text("error"));
        assertEquals("SCHEDULED", attempt(at).text("status"), "失败不得推进状态");

        jdbc.update("UPDATE staff SET certified=TRUE WHERE id=?", s2);
        Resp ok = startAttempt("MPST-s2", at, 1, W1S);
        assertEquals(200, ok.status());
        assertEquals("IN_PROGRESS", ok.text("status"));
        assertEquals(2, ok.body().get("version").asLong(), "成功变更版本+1");

        // 救援接报挂起后不得再开始
        assertEquals(200, report("MPST-r", e1, W1S.plusSeconds(600)).status());
        assertEquals("SUSPENDED", attempt(at).text("status"));
        Resp suspended = startAttempt("MPST-s3", at, 3, W1S.plusSeconds(1200));
        assertEquals(422, suspended.status());
        assertEquals("ILLEGAL_STATE", suspended.text("error"));
    }

    @Test
    @DisplayName("主流程：救援原子挂起保留清单，结案后续派新尝试，双人确认登记唯一有效记录")
    void rescueSuspendsThenRedispatchCompletes() throws Exception {
        long e1 = createElevator("MP-FLOW-A", LocalDate.of(2026, 1, 1));
        long s1 = createStaff("MP-F-1", true);
        long s2 = createStaff("MP-F-2", true);
        long ob = scanOpenObligation("MPF", e1, OPEN_AT);
        long at1 = dispatchAttempt("MPF-d1", ob, 1, List.of(s1, s2), W1S, W1E).id();
        assertEquals(200, startAttempt("MPF-st1", at1, 1, W1S).status());
        assertEquals(200, reportItems("MPF-i1", at1, 2, List.of("BRAKE_CHECK")).status());

        // 接报救援：原子挂起未终结尝试、保留已做清单、义务不变
        long rescueId = report("MPF-r", e1, W1S.plusSeconds(600)).id();
        var suspended = attempt(at1).body();
        assertEquals("SUSPENDED", suspended.get("status").asText());
        assertEquals(1, suspended.get("doneItems").size(), "已做清单须保留");
        assertEquals("BRAKE_CHECK", suspended.get("doneItems").get(0).asText());
        assertEquals("OPEN", obligation(ob).text("status"), "到期义务不因救援改变");

        // 救援进行中不得续派
        Resp inFlight = dispatchAttempt("MPF-d2", ob, 2, List.of(s1, s2),
                W1E, W1E.plusSeconds(7200));
        assertEquals(422, inFlight.status());
        assertEquals("RESCUE_IN_FLIGHT", inFlight.text("error"));

        // 旧尝试回报只记迟到
        Resp late = completeAttempt("MPF-c1", at1, 4, List.of(s1, s2), W1S.plusSeconds(3600));
        assertEquals(200, late.status());
        assertEquals("LATE_REPORTED", late.text("status"));
        assertEquals("OPEN", obligation(ob).text("status"), "旧尝试回报不得终结义务");
        assertNull(elevator(e1).text("lastMaintenanceDay"), "迟到回报不得当本周期维保完成");

        // 救援结案后可续派同一义务的新尝试
        assertEquals(200, dispatch("MPF-rd", rescueId, List.of(s1, s2)).status());
        assertEquals(200, arrive(rescueId, W1S.plusSeconds(1200)).status());
        assertEquals(200, rescue(rescueId, List.of(s1, s2)).status());
        assertEquals(200, recheck(rescueId, true).status());
        assertEquals(200, close("MPF-rc", rescueId).status());

        long at2 = dispatchAttempt("MPF-d3", ob, 2, List.of(s1, s2),
                W1E, W1E.plusSeconds(14400)).id();
        assertEquals(200, startAttempt("MPF-st2", at2, 1, W1E).status());
        assertEquals(200, reportItems("MPF-i2", at2, 2,
                List.of("BRAKE_CHECK", "DOOR_INTERLOCK", "ALARM_TEST")).status());
        Resp done = completeAttempt("MPF-c2", at2, 3, List.of(s1, s2),
                Instant.parse("2026-01-15T18:00:00Z"));
        assertEquals(200, done.status());
        assertEquals("COMPLETED", done.text("status"));
        assertNotNull(done.body().get("maintenanceRecordId"));

        assertEquals("FULFILLED", obligation(ob).text("status"), "完成终结义务");
        assertEquals("ATTEMPT", obligation(ob).text("settledBy"));
        assertEquals("2026-01-15", elevator(e1).text("lastMaintenanceDay"));
        assertEquals("RUNNING", elevator(e1).text("status"));

        Integer compliant = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_record WHERE elevator_id=? AND result='COMPLIANT'",
                Integer.class, e1);
        assertEquals(1, compliant, "仅登记一条有效维保记录");
        Integer lateEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_record WHERE elevator_id=? AND result='LATE_EVENT'",
                Integer.class, e1);
        assertEquals(1, lateEvents, "旧尝试回报只记一条迟到事件");

        // 轨迹查询：义务下两次尝试
        assertEquals(2, obligationAttempts(ob).body().size(), "义务轨迹含挂起与续派两次尝试");
    }

    @Test
    @DisplayName("完成确认：清单须全部完成、双人共同确认；未开始不得完成")
    void completionRequiresChecklistAndDualConfirm() throws Exception {
        long e1 = createElevator("MP-CMP-A", LocalDate.of(2026, 1, 1));
        long s1 = createStaff("MP-C-1", true);
        long s2 = createStaff("MP-C-2", true);
        long s3 = createStaff("MP-C-3", true);
        long ob = scanOpenObligation("MPC", e1, OPEN_AT);
        long at = dispatchAttempt("MPC-d", ob, 1, List.of(s1, s2), W1S, W1E).id();

        Resp notStarted = completeAttempt("MPC-c0", at, 1, List.of(s1, s2), W1S);
        assertEquals(422, notStarted.status(), "未开始不得完成");
        assertEquals("ILLEGAL_STATE", notStarted.text("error"));

        assertEquals(200, startAttempt("MPC-s", at, 1, W1S).status());
        assertEquals(200, reportItems("MPC-i1", at, 2, List.of("BRAKE_CHECK", "DOOR_INTERLOCK")).status());

        Resp incomplete = completeAttempt("MPC-c1", at, 3, List.of(s1, s2), W1S.plusSeconds(3600));
        assertEquals(422, incomplete.status(), "清单未全部完成不得登记");
        assertEquals("CHECKLIST_INCOMPLETE", incomplete.text("error"));

        assertEquals(200, reportItems("MPC-i2", at, 3, List.of("ALARM_TEST")).status());
        Resp wrongConfirm = completeAttempt("MPC-c2", at, 4, List.of(s1, s3), W1S.plusSeconds(3600));
        assertEquals(422, wrongConfirm.status(), "非作业双人确认不得登记");
        assertEquals("DUAL_CONFIRM_REQUIRED", wrongConfirm.text("error"));

        Resp unknownItem = reportItems("MPC-i3", at, 4, List.of("NO_SUCH_ITEM"));
        assertEquals(400, unknownItem.status(), "未知清单项应400");

        Resp ok = completeAttempt("MPC-c3", at, 4, List.of(s2, s1), Instant.parse("2026-01-15T10:00:00Z"));
        assertEquals(200, ok.status(), "双人共同确认（顺序无关）登记有效记录");
        assertEquals("COMPLETED", ok.text("status"));
        assertEquals("COMPLIANT", getJson("/api/elevators/" + e1 + "/maintenance")
                .body().get(0).get("result").asText());
    }

    @Test
    @DisplayName("逾期续派完成：登记逾期记录并锁定，不自动复梯，仍须原复检恢复流程")
    void overdueCompletionDoesNotAutoResume() throws Exception {
        long e1 = createElevator("MP-OD-A", LocalDate.of(2026, 1, 1));
        long s1 = createStaff("MP-OD-1", true);
        long s2 = createStaff("MP-OD-2", true);
        long ob = scanOpenObligation("MPOD", e1, OPEN_AT);
        long at = dispatchAttempt("MPOD-d", ob, 1, List.of(s1, s2), W1S, W1E).id();
        assertEquals(200, startAttempt("MPOD-s", at, 1, W1S).status());
        assertEquals(200, reportItems("MPOD-i", at, 2,
                List.of("BRAKE_CHECK", "DOOR_INTERLOCK", "ALARM_TEST")).status());

        // 到期日 2026-01-16，2026-01-17 完成属逾期
        Resp done = completeAttempt("MPOD-c", at, 3, List.of(s1, s2),
                Instant.parse("2026-01-17T10:00:00Z"));
        assertEquals(200, done.status());
        assertEquals("COMPLETED", done.text("status"));
        assertEquals("FULFILLED", obligation(ob).text("status"));

        assertEquals("STOPPED", elevator(e1).text("status"), "逾期应锁定且不自动复梯");
        assertTrue(elevator(e1).bool("keySupervision"));
        assertTrue(chainHas(e1, "STOP", "MAINTENANCE_OVERDUE"));
        assertEquals("OVERDUE", getJson("/api/elevators/" + e1 + "/maintenance")
                .body().get(0).get("result").asText());

        assertEquals(422, elevatorRecheck(e1, false).status(), "复检不合格禁止复梯");
        assertEquals("STOPPED", elevator(e1).text("status"));
        Resp ok = elevatorRecheck(e1, true);
        assertEquals(200, ok.status(), "仍须原复检恢复流程复梯");
        assertEquals("RUNNING", ok.text("status"));
        assertTrue(chainHas(e1, "RESUME", "RECHECK_PASS"));
    }

    @Test
    @DisplayName("旧手工维保入口兼容：有效登记将未完成义务结清一次并取消未终结尝试")
    void manualRegisterSettlesObligationOnce() throws Exception {
        long e1 = createElevator("MP-MAN-A", LocalDate.of(2026, 1, 1));
        long s1 = createStaff("MP-M-1", true);
        long s2 = createStaff("MP-M-2", true);
        long ob = scanOpenObligation("MPM", e1, OPEN_AT);
        long at = dispatchAttempt("MPM-d", ob, 1, List.of(s1, s2), W1S, W1E).id();

        Resp manual = maintenance("MPM-m1", e1, LocalDate.of(2026, 1, 10), null);
        assertEquals(200, manual.status());
        assertEquals("COMPLIANT", manual.text("result"));

        var settled = obligation(ob).body();
        assertEquals("SETTLED", settled.get("status").asText(), "手工有效登记应结清未完成义务");
        assertEquals("MANUAL", settled.get("settledBy").asText());
        assertEquals(3, settled.get("version").asLong(), "派发+1、结清+1，版本从1变到3");
        assertEquals("CANCELLED", attempt(at).text("status"), "未终结尝试应取消并释放人员");

        // 再次手工登记（下一周期合规）：义务已结清，不得重复结清
        Resp second = maintenance("MPM-m2", e1, LocalDate.of(2026, 1, 25), null);
        assertEquals(200, second.status());
        assertEquals("COMPLIANT", second.text("result"));
        var after = obligation(ob).body();
        assertEquals("SETTLED", after.get("status").asText());
        assertEquals(3, after.get("version").asLong(), "结清一次后版本不再变化");
    }

    @Test
    @DisplayName("派发幂等：同键同参重放冻结结果，异参409，失败不占键")
    void dispatchIdempotency() throws Exception {
        long e1 = createElevator("MP-IDEM-A", LocalDate.of(2026, 1, 1));
        long e2 = createElevator("MP-IDEM-B", LocalDate.of(2026, 1, 1));
        long s1 = createStaff("MP-I-1", true);
        long s2 = createStaff("MP-I-2", true);
        long ob1 = scanOpenObligation("MPI-1", e1, OPEN_AT);
        long ob2 = scanOpenObligation("MPI-2", e2, OPEN_AT);

        Resp first = dispatchAttempt("MPI-d", ob1, 1, List.of(s1, s2), W1S, W1E);
        assertEquals(200, first.status());
        Resp replay = dispatchAttempt("MPI-d", ob1, 1, List.of(s1, s2), W1S, W1E);
        assertEquals(200, replay.status(), "同键同参应重放首次冻结结果");
        assertEquals(om.writeValueAsString(first.body()), om.writeValueAsString(replay.body()));
        assertEquals(1, obligationAttempts(ob1).body().size(), "重放不得重复派发");

        Resp conflict = dispatchAttempt("MPI-d", ob1, 1, List.of(s1, s2), W1S, W1E.plusSeconds(3600));
        assertEquals(409, conflict.status(), "同键异参应409");
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.text("error"));

        // 失败不占键：先以重叠窗失败，再同键换合法参数成功
        Resp fail = dispatchAttempt("MPI-f", ob2, 1, List.of(s1, s2), W1S, W1E);
        assertEquals(422, fail.status(), "人员窗重叠应失败");
        Resp retry = dispatchAttempt("MPI-f", ob2, 1, List.of(s1, s2), W1E, W1E.plusSeconds(7200));
        assertEquals(200, retry.status(), "失败不占键，同键异参可重新受理");
    }

    @Test
    @DisplayName("对象不存在404；参数非法400")
    void notFoundAndBadRequest() throws Exception {
        Resp ob = obligation(424242);
        assertEquals(404, ob.status());
        Resp at = attempt(424242);
        assertEquals(404, at.status());
        Resp dispatch404 = dispatchAttempt("NFD-d", 424242, 1, List.of(1L, 2L), W1S, W1E);
        assertEquals(404, dispatch404.status());

        Resp badBody = postJson("/api/maintenance-obligations/scan",
                java.util.Map.of("scannedAt", OPEN_AT.toString()));
        assertEquals(400, badBody.status(), "缺少 requestId 应400");
    }
}
