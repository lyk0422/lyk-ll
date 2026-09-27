package com.example.starter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 救援抢占后的履约续派：接报原子挂起未完成尝试并释放人员、保留清单；
 * 结案后续派同一义务的新尝试，旧尝试回报只记迟到；新尝试双人确认才登记有效维保；
 * 逾期续派不自动复梯，仍走原复检流程。
 */
class MaintenancePreemptionTest extends AbstractIntegrationTest {

    private static final LocalDate COMMISSIONED = LocalDate.of(2026, 1, 1);
    private static final Instant DISPATCHABLE = Instant.parse("2026-01-15T00:00:00Z");
    private static final Instant RESCUE_AT = Instant.parse("2026-01-15T03:00:00Z");

    @Test
    @DisplayName("接报挂起尝试并释放人员；结案后续派新尝试完成，旧尝试回报仅迟到")
    void suspendAndRedispatchAfterRescue() throws Exception {
        long e1 = createElevator("PM-A", COMMISSIONED);
        long s1 = createStaff("C-PM-1", true);
        long s2 = createStaff("C-PM-2", true);
        long s3 = createStaff("C-PM-3", true);
        long s4 = createStaff("C-PM-4", true);
        planScan("PM-scan", DISPATCHABLE);
        long obId = firstObligationId(e1);

        // 第一次尝试 A：完成 I-1 后被救援抢占
        Resp a = planDispatch("PM-a", e1, obId, List.of(s1, s2), twoItems(),
                DISPATCHABLE, Instant.parse("2026-01-16T12:00:00Z"),
                Instant.parse("2026-01-15T01:00:00Z"));
        assertEquals(200, a.status(), a.body() == null ? "" : a.body().toString());
        long attemptA = a.id();
        assertEquals(200, reportItem(attemptA, "PM-a-i1", "I-1", true, 1,
                Instant.parse("2026-01-15T02:00:00Z")).status());

        // 接报救援：原子挂起 A
        Resp rep = report("PM-rescue", e1, RESCUE_AT);
        assertEquals(200, rep.status());
        long rescueId = rep.id();
        Resp suspended = planAttempt(attemptA);
        assertEquals("SUSPENDED", suspended.text("status"));
        assertEquals(rescueId, suspended.body().get("rescueId").asLong());
        assertTrue(suspended.body().get("items").get(0).get("done").asBoolean(), "已做清单须保留");
        assertFalse(suspended.body().get("items").get(1).get("done").asBoolean());
        assertEquals("OPEN", planObligation(obId).text("status"), "挂起不改变到期义务");
        assertTrue(planTimeline(attemptA).body().findValuesAsText("event").contains("SUSPENDED"));

        // 救援进行中不得续派
        Resp duringRescue = planDispatch("PM-b-early", e1, obId, List.of(s1, s2), twoItems(),
                Instant.parse("2026-01-15T05:00:00Z"), Instant.parse("2026-01-16T12:00:00Z"),
                Instant.parse("2026-01-15T05:00:00Z"));
        assertEquals(422, duringRescue.status());
        assertEquals("RESCUE_IN_FLIGHT", duringRescue.text("error"));

        // 旧尝试 A 的清单/完成回报只记迟到，版本冻结在挂起时的 3，不终结义务
        Resp lateItem = reportItem(attemptA, "PM-a-i2-late", "I-2", true, 3,
                Instant.parse("2026-01-15T03:30:00Z"));
        assertEquals(200, lateItem.status());
        assertFalse(lateItem.body().get("items").get(1).get("done").asBoolean(), "迟到回报不得补做清单");
        assertEquals(3, lateItem.body().get("version").asInt(), "迟到回报不变更版本");
        Resp lateComplete = planComplete(attemptA, "PM-a-complete-late", List.of(s1, s2), 3,
                Instant.parse("2026-01-15T04:00:00Z"));
        assertEquals(200, lateComplete.status());
        assertEquals("SUSPENDED", lateComplete.text("status"));
        assertTrue(planTimeline(attemptA).body().findValuesAsText("event").contains("LATE_REPORT"));
        assertEquals("OPEN", planObligation(obId).text("status"), "迟到确认不得终结义务");

        // 救援结案（另派 s3/s4，复检合格复梯）
        assertEquals(200, dispatch("PM-d", rescueId, List.of(s3, s4)).status());
        assertEquals(200, arrive(rescueId, RESCUE_AT.plusSeconds(600)).status());
        assertEquals(200, rescue(rescueId, List.of(s3, s4)).status());
        assertEquals(200, recheck(rescueId, true).status());
        assertEquals(200, close("PM-close", rescueId).status());
        assertEquals("RUNNING", elevator(e1).text("status"));

        // 结案后续派同一义务新尝试 B；A 已挂起不占人员，s1/s2 可再派
        Resp b = planDispatch("PM-b", e1, obId, List.of(s1, s2), twoItems(),
                Instant.parse("2026-01-15T05:00:00Z"), Instant.parse("2026-01-16T12:00:00Z"),
                Instant.parse("2026-01-15T05:00:00Z"));
        assertEquals(200, b.status(), "结案后应可续派同一义务: " + b.body());
        long attemptB = b.id();
        assertTrue(attemptB != attemptA);
        for (var item : b.body().get("items")) {
            assertFalse(item.get("done").asBoolean(), "新尝试清单须从零完成");
        }

        // 新尝试完成全部清单并双人确认
        assertEquals(200, reportItem(attemptB, "PM-b-i1", "I-1", true, 1,
                Instant.parse("2026-01-15T05:30:00Z")).status());
        assertEquals(200, reportItem(attemptB, "PM-b-i2", "I-2", true, 2,
                Instant.parse("2026-01-15T06:00:00Z")).status());
        Resp done = planComplete(attemptB, "PM-b-complete", List.of(s1, s2), 3,
                Instant.parse("2026-01-15T06:30:00Z"));
        assertEquals(200, done.status());
        assertEquals("COMPLETED", done.text("status"));

        Resp ob = planObligation(obId);
        assertEquals("FULFILLED", ob.text("status"));
        assertEquals("ATTEMPT", ob.text("settleSource"));
        assertEquals(attemptB, ob.body().get("completedAttemptId").asLong());
        Integer validRecords = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_record WHERE elevator_id=? AND result='COMPLIANT' "
                        + "AND attempt_id=?", Integer.class, e1, attemptB);
        assertEquals(1, validRecords, "仅新尝试 B 登记一条有效维保记录");
        assertEquals("SUSPENDED", planAttempt(attemptA).text("status"), "旧尝试保持挂起");

        // 终结后旧尝试再回报仍只记迟到，不新增记录
        Resp veryLate = planComplete(attemptA, "PM-a-complete-verylate", List.of(s1, s2), 3,
                Instant.parse("2026-01-15T07:00:00Z"));
        assertEquals(200, veryLate.status());
        assertEquals("SUSPENDED", veryLate.text("status"));
        Integer totalRecords = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_record WHERE elevator_id=?", Integer.class, e1);
        assertEquals(1, totalRecords, "迟到回报不得登记维保记录");
    }

    @Test
    @DisplayName("逾期续派完成不自动复梯：登记逾期记录、锁定重点监察，仍须原电梯复检流程恢复")
    void overdueRedispatchDoesNotAutoResume() throws Exception {
        long e1 = createElevator("PM-OD", COMMISSIONED);
        long s1 = createStaff("C-POD-1", true);
        long s2 = createStaff("C-POD-2", true);
        long s3 = createStaff("C-POD-3", true);
        long s4 = createStaff("C-POD-4", true);
        planScan("PM-od-scan", DISPATCHABLE);
        long obId = firstObligationId(e1);

        Resp a = planDispatch("PM-od-a", e1, obId, List.of(s1, s2), twoItems(),
                DISPATCHABLE, Instant.parse("2026-01-17T12:00:00Z"), DISPATCHABLE);
        assertEquals(200, a.status());
        long attemptA = a.id();
        // 接报→结案（救援发生在到期前，复检后恢复运行）
        long rescueId = report("PM-od-rescue", e1, RESCUE_AT).id();
        assertEquals("SUSPENDED", planAttempt(attemptA).text("status"));
        assertEquals(200, dispatch("PM-od-d", rescueId, List.of(s3, s4)).status());
        assertEquals(200, arrive(rescueId, RESCUE_AT.plusSeconds(600)).status());
        assertEquals(200, rescue(rescueId, List.of(s3, s4)).status());
        assertEquals(200, recheck(rescueId, true).status());
        assertEquals(200, close("PM-od-close", rescueId).status());
        assertEquals("RUNNING", elevator(e1).text("status"), "救援复检后应已恢复运行");

        // 逾期续派：作业窗跨到期点，完成时刻为 2026-01-17（应维保截止 2026-01-16）
        Instant bStart = Instant.parse("2026-01-17T01:00:00Z");
        Resp b = planDispatch("PM-od-b", e1, obId, List.of(s1, s2), twoItems(),
                bStart, Instant.parse("2026-01-17T12:00:00Z"), bStart);
        assertEquals(200, b.status());
        long attemptB = b.id();
        assertEquals(200, reportItem(attemptB, "PM-od-i1", "I-1", true, 1, bStart.plusSeconds(60)).status());
        assertEquals(200, reportItem(attemptB, "PM-od-i2", "I-2", true, 2, bStart.plusSeconds(120)).status());
        Resp done = planComplete(attemptB, "PM-od-complete", List.of(s1, s2), 3, bStart.plusSeconds(180));
        assertEquals(200, done.status());

        assertEquals("STOPPED", elevator(e1).text("status"), "逾期续派完成不得自动复梯");
        assertTrue(elevator(e1).bool("keySupervision"));
        Integer overdueRecords = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_record WHERE elevator_id=? AND attempt_id=? AND result='OVERDUE'",
                Integer.class, e1, attemptB);
        assertEquals(1, overdueRecords, "应登记一条逾期有效维保记录");
        assertTrue(chainHas(e1, "STOP", "MAINTENANCE_OVERDUE"));

        // 仍须原复检恢复流程
        Resp failRecheck = elevatorRecheck(e1, false);
        assertEquals(422, failRecheck.status());
        assertEquals("STOPPED", elevator(e1).text("status"));
        Resp okRecheck = elevatorRecheck(e1, true);
        assertEquals(200, okRecheck.status());
        assertEquals("RUNNING", okRecheck.text("status"));
    }
}
