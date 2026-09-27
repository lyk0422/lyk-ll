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
 * 周期维保计划编排：扫描义务（到期前24小时，恰在起点允许）、派发尝试（两名持证人员+
 * 半开作业窗）、清单与双人确认完成、救援抢占挂起与结案后续派、手工登记结清义务、
 * 逾期续派不自动复梯、幂等与版本边界。
 */
class MaintenancePlanTest extends AbstractIntegrationTest {

    private static final LocalDate COMMISSIONED = LocalDate.of(2026, 1, 1);
    /** 到期时刻 = 2026-01-16T00:00:00Z；派发窗口起点 = 2026-01-15T00:00:00Z */
    private static final Instant DUE_AT = Instant.parse("2026-01-16T00:00:00Z");
    private static final Instant WINDOW_OPEN = Instant.parse("2026-01-15T00:00:00Z");
    private static final Instant WS = Instant.parse("2026-01-15T08:00:00Z");
    private static final Instant WE = Instant.parse("2026-01-15T12:00:00Z");

    private long scanCreated(String requestId, long elevatorId, Instant now) throws Exception {
        Resp r = scan(requestId, elevatorId, now);
        assertEquals(200, r.status());
        assertTrue(r.bool("created"), "扫描应创建义务");
        return r.body().get("obligationId").asLong();
    }

    @Test
    @DisplayName("扫描：恰在到期前24小时起点允许创建，提前1秒不创建，重复扫描不多派")
    void scanWindowBoundaryAndNoDuplicate() throws Exception {
        long e1 = createElevator("PS-A", COMMISSIONED);
        // 起点前 1 秒：不创建
        Resp early = scan("PS-s0", e1, WINDOW_OPEN.minusSeconds(1));
        assertEquals(200, early.status());
        assertFalse(early.bool("created"), "窗口未开始不得创建义务");
        assertEquals("2026-01-16T00:00:00Z", early.text("dueAt"));

        // 恰在起点：允许
        long obId = scanCreated("PS-s1", e1, WINDOW_OPEN);
        Resp ob = obligation(obId);
        assertEquals("OPEN", ob.text("status"));
        assertEquals(1, ob.body().get("version").asLong(), "版本从1开始");
        assertEquals("2026-01-16T00:00:00Z", ob.text("dueAt"));
        assertNull(ob.text("baseMaintenanceId"), "未维保过基准记录为NULL");

        // 重复扫描（新 requestId）：不多派
        Resp again = scan("PS-s2", e1, WINDOW_OPEN.plusSeconds(3600));
        assertEquals(200, again.status());
        assertFalse(again.bool("created"));
        assertEquals(obId, again.body().get("obligationId").asLong());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_obligation WHERE elevator_id=?", Integer.class, e1);
        assertEquals(1, count, "同一电梯同一到期周期只能一项义务");

        // 同键同参重放：返回首次冻结结果
        Resp replay = scan("PS-s1", e1, WINDOW_OPEN);
        assertEquals(200, replay.status());
        assertTrue(replay.bool("created"), "同键重放应返回首次冻结结果");
        // 同键异参：409
        Resp conflict = scan("PS-s1", e1, WINDOW_OPEN.plusSeconds(1));
        assertEquals(409, conflict.status());
    }

    @Test
    @DisplayName("主流程：派发→全部清单→双人共同确认→登记有效维保并结清义务")
    void fullPlanLifecycle() throws Exception {
        long e1 = createElevator("PL-A", COMMISSIONED);
        long s1 = createStaff("PL-1", true);
        long s2 = createStaff("PL-2", true);
        long obId = scanCreated("PL-scan", e1, WINDOW_OPEN);

        Resp att = dispatchAttempt(obId, "PL-d1", 1, List.of(s1, s2), WS, WE);
        assertEquals(200, att.status());
        long attemptId = att.id();
        assertEquals("DISPATCHED", att.text("status"));
        assertEquals(1, att.body().get("attemptNo").asInt());
        assertEquals(3, att.body().get("checklistTotal").asInt());
        assertEquals(2, obligation(obId).body().get("version").asLong(), "派发成功义务版本+1");

        // 清单逐项回报，版本随之递增
        long v = 1;
        for (String item : List.of("SYN-CHECK-1", "SYN-CHECK-2", "SYN-CHECK-3")) {
            Resp c = checklist(attemptId, "PL-c-" + item, v, item, Instant.parse("2026-01-15T09:00:00Z"));
            assertEquals(200, c.status());
            v = c.body().get("version").asLong();
            assertEquals("DISPATCHED", c.text("status"), "未双人确认不得完成");
        }
        // 第一人确认：仍不完成
        Resp c1 = confirm(attemptId, "PL-cf1", v, s1, Instant.parse("2026-01-15T10:00:00Z"));
        assertEquals(200, c1.status());
        assertEquals("DISPATCHED", c1.text("status"));
        // 第二人确认：完成
        Resp c2 = confirm(attemptId, "PL-cf2", c1.body().get("version").asLong(), s2,
                Instant.parse("2026-01-15T10:30:00Z"));
        assertEquals(200, c2.status());
        assertEquals("COMPLETED", c2.text("status"));
        assertNotNull(c2.text("completedAt"));

        Resp ob = obligation(obId);
        assertEquals("FULFILLED", ob.text("status"), "完成应终结义务");
        assertNotNull(ob.text("fulfilledAt"));
        assertEquals("2026-01-15", elevator(e1).text("lastMaintenanceDay"));
        assertEquals("RUNNING", elevator(e1).text("status"));

        // 恰一条有效维保记录
        Integer compliant = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_record WHERE elevator_id=? AND result='COMPLIANT'",
                Integer.class, e1);
        assertEquals(1, compliant, "双人确认后登记一条有效维保记录");
        // 尝试轨迹可查
        Resp detail = attemptDetail(attemptId);
        assertEquals(2, detail.body().get("staff").size());
        assertTrue(detail.body().get("staff").get(0).get("confirmed").asBoolean());
        assertEquals(3, detail.body().get("checklist").size());
        assertEquals(1, attempts(obId).body().size());
    }

    @Test
    @DisplayName("派发失败分支：人数/窗口非法400，未持证/重叠/进行中422，版本冲突409，义务不存在404")
    void dispatchFailureBranches() throws Exception {
        long e1 = createElevator("PF-A", COMMISSIONED);
        long e2 = createElevator("PF-B", COMMISSIONED);
        long s1 = createStaff("PF-1", true);
        long s2 = createStaff("PF-2", true);
        long s3 = createStaff("PF-3", false);
        long ob1 = scanCreated("PF-scan1", e1, WINDOW_OPEN);
        long ob2 = scanCreated("PF-scan2", e2, WINDOW_OPEN);

        assertEquals(404, dispatchAttempt(99999, "PF-d404", 1, List.of(s1, s2), WS, WE).status());
        assertEquals(400, dispatchAttempt(ob1, "PF-d1p", 1, List.of(s1), WS, WE).status(), "单人非法");
        assertEquals(400, dispatchAttempt(ob1, "PF-dw", 1, List.of(s1, s2), WE, WS).status(), "窗口非法");
        assertEquals(422, dispatchAttempt(ob1, "PF-du", 1, List.of(s1, s3), WS, WE).status(), "未持证");
        assertEquals(404, dispatchAttempt(ob1, "PF-dnf", 1, List.of(s1, 99999L), WS, WE).status());
        assertEquals(409, dispatchAttempt(ob1, "PF-dv", 99, List.of(s1, s2), WS, WE).status(), "版本冲突");

        // 正常派发占窗
        assertEquals(200, dispatchAttempt(ob1, "PF-d1", 1, List.of(s1, s2), WS, WE).status());
        // 半开窗重叠：另一义务用同一人员 [11:00,13:00) 与 [08:00,12:00) 重叠 → 整批不占
        Resp overlap = dispatchAttempt(ob2, "PF-d2", 1, List.of(s1, s2),
                Instant.parse("2026-01-15T11:00:00Z"), Instant.parse("2026-01-15T13:00:00Z"));
        assertEquals(422, overlap.status());
        assertEquals("STAFF_WINDOW_OVERLAP", overlap.text("error"));
        Integer ob2Attempts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt WHERE obligation_id=?", Integer.class, ob2);
        assertEquals(0, ob2Attempts, "任一不足整批不占资源");
        // 半开窗边界相接 [12:00,14:00) 不重叠 → 允许
        assertEquals(200, dispatchAttempt(ob2, "PF-d3", 1, List.of(s1, s2),
                WE, Instant.parse("2026-01-15T14:00:00Z")).status(), "半开窗相接不重叠");
        // 同义务已有进行中尝试
        assertEquals(422, dispatchAttempt(ob1, "PF-d4", 2, List.of(s1, s2),
                Instant.parse("2026-01-16T00:00:00Z"), Instant.parse("2026-01-16T04:00:00Z")).status());
    }

    @Test
    @DisplayName("清单/确认失败分支：未知清单项422，非本尝试人员422，挂起后清单回报422")
    void checklistAndConfirmFailures() throws Exception {
        long e1 = createElevator("PC-A", COMMISSIONED);
        long s1 = createStaff("PC-1", true);
        long s2 = createStaff("PC-2", true);
        long s3 = createStaff("PC-3", true);
        long obId = scanCreated("PC-scan", e1, WINDOW_OPEN);
        long attemptId = dispatchAttempt(obId, "PC-d1", 1, List.of(s1, s2), WS, WE).id();

        assertEquals(422, checklist(attemptId, "PC-cx", 1, "NO-SUCH-ITEM", null).status());
        assertEquals(409, checklist(attemptId, "PC-cv", 7, "SYN-CHECK-1", null).status());
        assertEquals(422, confirm(attemptId, "PC-cf-x", 1, s3, null).status(), "非本尝试人员");
        assertEquals(404, confirm(99999, "PC-cf-404", 1, s1, null).status());

        // 接报救援挂起后：清单回报拒绝
        assertEquals(200, report("PC-r1", e1, Instant.parse("2026-01-15T09:00:00Z")).status());
        Resp c = checklist(attemptId, "PC-c1", 1, "SYN-CHECK-1", null);
        assertEquals(422, c.status());
        assertEquals("ATTEMPT_NOT_ACTIVE", c.text("error"));
    }

    @Test
    @DisplayName("救援抢占：原子挂起释放人员保留清单；旧尝试回报只记迟到；结案后续派完成")
    void rescuePreemptionAndResume() throws Exception {
        long e1 = createElevator("PR-A", COMMISSIONED);
        long e2 = createElevator("PR-B", COMMISSIONED);
        long s1 = createStaff("PR-1", true);
        long s2 = createStaff("PR-2", true);
        long obId = scanCreated("PR-scan", e1, WINDOW_OPEN);
        long attemptId = dispatchAttempt(obId, "PR-d1", 1, List.of(s1, s2), WS, WE).id();
        // 已做 1 项清单
        assertEquals(200, checklist(attemptId, "PR-c1", 1, "SYN-CHECK-1",
                Instant.parse("2026-01-15T08:30:00Z")).status());

        // 接报救援：原子挂起
        long rescueId = report("PR-r1", e1, Instant.parse("2026-01-15T09:00:00Z")).id();
        Resp att = attemptDetail(attemptId);
        assertEquals("SUSPENDED", att.body().get("attempt").get("status").asText());
        assertEquals(rescueId, att.body().get("attempt").get("suspendRescueId").asLong());
        assertEquals(1, att.body().get("attempt").get("checklistDone").asInt(), "已做清单保留");
        assertEquals("OPEN", obligation(obId).text("status"), "挂起不改变到期义务");

        // 人员已释放：同一人员重叠窗口可为另一电梯义务派发
        long ob2 = scanCreated("PR-scan2", e2, WINDOW_OPEN);
        assertEquals(200, dispatchAttempt(ob2, "PR-d2", 1, List.of(s1, s2), WS, WE).status(),
                "挂起后人员应已释放");

        // 旧尝试回报：只记迟到，不当本周期完成
        Resp late = confirm(attemptId, "PR-cf-late", 3, s1, Instant.parse("2026-01-15T10:00:00Z"));
        assertEquals(200, late.status());
        assertEquals("LATE_CLOSED", late.text("status"));
        Integer lateEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_record WHERE elevator_id=? AND result='LATE_EVENT'",
                Integer.class, e1);
        assertEquals(1, lateEvents, "旧尝试回报只记迟到事件");
        assertEquals("OPEN", obligation(obId).text("status"), "迟到回报不得结清义务");
        assertNull(elevator(e1).text("lastMaintenanceDay"), "迟到回报不得变更台账");

        // 救援结案
        assertEquals(200, dispatch("PR-rd", rescueId, List.of(s1, s2)).status());
        assertEquals(200, arrive(rescueId, Instant.parse("2026-01-15T09:10:00Z")).status());
        assertEquals(200, rescue(rescueId, List.of(s1, s2)).status());
        assertEquals(200, recheck(rescueId, true).status());
        assertEquals(200, close("PR-rc", rescueId).status());

        // 结案后续派同一义务的新尝试（义务版本：scan1 + dispatch1 = 2；
        // 人员仍被 e2 尝试占用 [08:00,12:00)，续派使用相接的半开窗 [12:00,14:00)）
        Resp att2 = dispatchAttempt(obId, "PR-d3", 2, List.of(s1, s2),
                WE, Instant.parse("2026-01-15T14:00:00Z"));
        assertEquals(200, att2.status(), "结案后可续派同一义务的新尝试");
        assertEquals(2, att2.body().get("attemptNo").asInt());
        long attempt2 = att2.id();
        long v = 1;
        for (String item : List.of("SYN-CHECK-1", "SYN-CHECK-2", "SYN-CHECK-3")) {
            v = checklist(attempt2, "PR-c2-" + item, v, item, Instant.parse("2026-01-15T10:00:00Z"))
                    .body().get("version").asLong();
        }
        v = confirm(attempt2, "PR-cf2-1", v, s1, Instant.parse("2026-01-15T11:00:00Z"))
                .body().get("version").asLong();
        Resp done = confirm(attempt2, "PR-cf2-2", v, s2, Instant.parse("2026-01-15T11:30:00Z"));
        assertEquals("COMPLETED", done.text("status"));
        assertEquals("FULFILLED", obligation(obId).text("status"));
        assertEquals("2026-01-15", elevator(e1).text("lastMaintenanceDay"));
        Integer compliant = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_record WHERE elevator_id=? AND result='COMPLIANT'",
                Integer.class, e1);
        assertEquals(1, compliant, "新尝试完成登记一条有效维保记录");
        assertEquals(2, attempts(obId).body().size(), "义务下应有两次尝试轨迹");
    }

    @Test
    @DisplayName("手工维保登记兼容：有效登记将对应未完成义务结清一次，进行中尝试被取代")
    void manualRegistrationSettlesObligation() throws Exception {
        long e1 = createElevator("PM-A", COMMISSIONED);
        long s1 = createStaff("PM-1", true);
        long s2 = createStaff("PM-2", true);
        long obId = scanCreated("PM-scan", e1, WINDOW_OPEN);
        long attemptId = dispatchAttempt(obId, "PM-d1", 1, List.of(s1, s2), WS, WE).id();

        Resp m = maintenance("PM-m1", e1, LocalDate.of(2026, 1, 15), null);
        assertEquals(200, m.status());
        assertEquals("COMPLIANT", m.text("result"));
        assertEquals("FULFILLED", obligation(obId).text("status"), "手工有效登记应结清义务");
        assertEquals("SUPERSEDED", attemptDetail(attemptId).body().get("attempt").get("status").asText(),
                "进行中尝试应被取代并释放人员");

        // 被取代尝试的回报只记迟到
        Resp late = confirm(attemptId, "PM-cf-late", 2, s1, Instant.parse("2026-01-15T10:00:00Z"));
        assertEquals(200, late.status());
        assertEquals("LATE_CLOSED", late.text("status"));
        // 义务已结清：不得再派发
        assertEquals(422, dispatchAttempt(obId, "PM-d2", 3, List.of(s1, s2), WS, WE).status());
    }

    @Test
    @DisplayName("逾期续派完成：登记OVERDUE并锁定，不自动复梯，须原复检恢复流程")
    void overdueCompletionDoesNotAutoResume() throws Exception {
        long e1 = createElevator("PO-A", COMMISSIONED);
        long s1 = createStaff("PO-1", true);
        long s2 = createStaff("PO-2", true);
        long obId = scanCreated("PO-scan", e1, WINDOW_OPEN);
        long attemptId = dispatchAttempt(obId, "PO-d1", 1, List.of(s1, s2), WS, WE).id();

        // 到期日后才完成：2026-01-17 > 2026-01-16 → 逾期
        long v = 1;
        for (String item : List.of("SYN-CHECK-1", "SYN-CHECK-2", "SYN-CHECK-3")) {
            v = checklist(attemptId, "PO-c-" + item, v, item, Instant.parse("2026-01-17T09:00:00Z"))
                    .body().get("version").asLong();
        }
        v = confirm(attemptId, "PO-cf1", v, s1, Instant.parse("2026-01-17T10:00:00Z"))
                .body().get("version").asLong();
        Resp done = confirm(attemptId, "PO-cf2", v, s2, Instant.parse("2026-01-17T10:30:00Z"));
        assertEquals("COMPLETED", done.text("status"));

        Integer overdue = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_record WHERE elevator_id=? AND result='OVERDUE'",
                Integer.class, e1);
        assertEquals(1, overdue, "逾期完成应登记 OVERDUE 记录");
        assertEquals("STOPPED", elevator(e1).text("status"), "逾期锁定且不自动复梯");
        assertTrue(elevator(e1).bool("keySupervision"));
        assertTrue(chainHas(e1, "STOP", "MAINTENANCE_OVERDUE"));
        assertEquals("FULFILLED", obligation(obId).text("status"));

        // 仍须原复检恢复流程
        assertEquals(422, elevatorRecheck(e1, false).status());
        assertEquals(200, elevatorRecheck(e1, true).status());
        assertEquals("RUNNING", elevator(e1).text("status"));
    }

    @Test
    @DisplayName("派发与确认幂等：同键同参重放首次结果，异参409，失败不占键")
    void planIdempotency() throws Exception {
        long e1 = createElevator("PI-A", COMMISSIONED);
        long s1 = createStaff("PI-1", true);
        long s2 = createStaff("PI-2", true);
        long obId = scanCreated("PI-scan", e1, WINDOW_OPEN);

        // 失败不占键：版本冲突后用同键修正参数可成功
        assertEquals(409, dispatchAttempt(obId, "PI-d1", 99, List.of(s1, s2), WS, WE).status());
        Resp first = dispatchAttempt(obId, "PI-d1", 1, List.of(s1, s2), WS, WE);
        assertEquals(200, first.status(), "失败不占键，同键修正后应成功");
        long attemptId = first.id();

        // 同键同参重放：返回首次冻结结果
        Resp replay = dispatchAttempt(obId, "PI-d1", 1, List.of(s1, s2), WS, WE);
        assertEquals(200, replay.status());
        assertEquals(om.writeValueAsString(first.body()), om.writeValueAsString(replay.body()));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt WHERE obligation_id=?", Integer.class, obId);
        assertEquals(1, count, "同键重放不得重复派发");
        // 同键异参：409（窗口时刻不同）
        assertEquals(409, dispatchAttempt(obId, "PI-d1", 1, List.of(s1, s2), WS,
                WE.plusSeconds(3600)).status());

        // 确认同键重放
        Resp c1 = confirm(attemptId, "PI-cf1", 1, s1, Instant.parse("2026-01-15T10:00:00Z"));
        assertEquals(200, c1.status());
        Resp c1Replay = confirm(attemptId, "PI-cf1", 1, s1, Instant.parse("2026-01-15T10:00:00Z"));
        assertEquals(200, c1Replay.status());
        assertEquals(om.writeValueAsString(c1.body()), om.writeValueAsString(c1Replay.body()));
        // 同键异参（确认人不同）：409
        assertEquals(409, confirm(attemptId, "PI-cf1", 1, s2,
                Instant.parse("2026-01-15T10:00:00Z")).status());
    }
}
