package com.example.starter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.example.starter.web.dto.ApiDtos.MaintenanceItemInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 周期维保计划编排主流程与失败分支：
 * 24 小时窗口扫描、唯一义务、双人持证/半开作业窗/同窗互斥、清单版本、双人确认登记有效记录。
 */
class MaintenancePlanFlowTest extends AbstractIntegrationTest {

    private static final LocalDate COMMISSIONED = LocalDate.of(2026, 1, 1);
    private static final Instant DUE = Instant.parse("2026-01-16T00:00:00Z");
    private static final Instant DISPATCHABLE = Instant.parse("2026-01-15T00:00:00Z");
    private static final Instant W_START = Instant.parse("2026-01-15T00:00:00Z");
    private static final Instant W_END = Instant.parse("2026-01-16T12:00:00Z");
    private static final Instant STARTED = Instant.parse("2026-01-15T01:00:00Z");

    private long dispatchAttempt(long elevatorId, long obligationId, long s1, long s2,
                                 List<MaintenanceItemInput> items, String key) throws Exception {
        Resp r = planDispatch(key, elevatorId, obligationId, List.of(s1, s2), items, W_START, W_END, STARTED);
        assertEquals(200, r.status(), "派发失败: " + r.status() + " " + r.body());
        return r.id();
    }

    @Test
    @DisplayName("到期前24小时恰在起点生成唯一义务；重复扫描不多派")
    void scanCreatesSingleObligationAt24HourBoundary() throws Exception {
        long e1 = createElevator("PL-SCAN", COMMISSIONED);

        Resp tooEarly = planScan("PL-scan-early", DISPATCHABLE.minusSeconds(1));
        assertEquals(200, tooEarly.status());
        assertEquals(0, tooEarly.body().size(), "距到期超过24小时不得编排义务");

        Resp atBoundary = planScan("PL-scan-1", DISPATCHABLE);
        assertEquals(200, atBoundary.status());
        assertEquals(1, atBoundary.body().size());
        long obligationId = atBoundary.body().get(0).get("id").asLong();
        assertEquals(DUE.toString(), atBoundary.body().get(0).get("dueAt").asText());
        assertEquals("OPEN", atBoundary.body().get(0).get("status").asText());
        assertEquals(1, atBoundary.body().get(0).get("version").asInt(), "版本从 1 开始");

        // 同键同参重放：返回首次冻结结果
        Resp replay = planScan("PL-scan-1", DISPATCHABLE);
        assertEquals(200, replay.status());
        assertEquals(om.writeValueAsString(atBoundary.body()), om.writeValueAsString(replay.body()));
        // 异键再扫：同周期不重复生成
        Resp again = planScan("PL-scan-2", DISPATCHABLE.plusSeconds(60));
        assertEquals(200, again.status());
        assertEquals(0, again.body().size(), "重复扫描不得多派义务");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_obligation WHERE elevator_id=?", Integer.class, e1);
        assertEquals(1, count, "同一到期周期只能一项义务");
        assertEquals(obligationId, firstObligationId(e1));
    }

    @Test
    @DisplayName("主流程：双人持证完成全部清单并共同确认，登记一条合规记录并终结义务")
    void fullAttemptFlowRegistersValidMaintenance() throws Exception {
        long e1 = createElevator("PL-MAIN", COMMISSIONED);
        long s1 = createStaff("C-PL-1", true);
        long s2 = createStaff("C-PL-2", true);
        planScan("PL-main-scan", DISPATCHABLE);
        long obId = firstObligationId(e1);
        long attemptId = dispatchAttempt(e1, obId, s1, s2, twoItems(), "PL-main-dispatch");

        assertEquals("PLANNED", planAttempt(attemptId).text("status"));
        assertEquals(1, planAttempt(attemptId).body().get("version").asInt());

        Resp i1 = reportItem(attemptId, "PL-item-1", "I-1", true, 1, STARTED.plusSeconds(600));
        assertEquals(200, i1.status());
        assertEquals(2, i1.body().get("version").asInt(), "成功变更后版本加 1");
        assertTrue(i1.body().get("items").get(0).get("done").asBoolean());

        // 过期版本回报被拒
        Resp stale = reportItem(attemptId, "PL-item-2", "I-2", true, 1, STARTED.plusSeconds(900));
        assertEquals(409, stale.status());
        assertEquals("VERSION_CONFLICT", stale.text("error"));

        // 清单未全部完成不得确认
        Resp incomplete = planComplete(attemptId, "PL-complete-try", List.of(s1, s2), 2,
                STARTED.plusSeconds(1200));
        assertEquals(422, incomplete.status());
        assertEquals("CHECKLIST_INCOMPLETE", incomplete.text("error"));

        Resp i2 = reportItem(attemptId, "PL-item-2", "I-2", true, 2, STARTED.plusSeconds(1500));
        assertEquals(200, i2.status());
        assertEquals(3, i2.body().get("version").asInt());

        // 确认双人与派发双人不一致
        Resp wrongPair = planComplete(attemptId, "PL-complete-wrong", List.of(s1, s1), 3,
                STARTED.plusSeconds(1800));
        assertEquals(400, wrongPair.status(), "去重后不足两人应 400");

        Instant completedAt = STARTED.plusSeconds(2400);
        Resp done = planComplete(attemptId, "PL-complete", List.of(s1, s2), 3, completedAt);
        assertEquals(200, done.status(), "双人共同确认应成功: " + done.body());
        assertEquals("COMPLETED", done.text("status"));
        assertEquals(4, done.body().get("version").asInt(), "完成后版本再加 1");

        Resp ob = planObligation(obId);
        assertEquals("FULFILLED", ob.text("status"));
        assertEquals("ATTEMPT", ob.text("settleSource"));
        assertEquals(attemptId, ob.body().get("completedAttemptId").asLong());
        assertEquals(2, ob.body().get("version").asInt(), "义务终结后版本加 1");

        Integer records = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_record WHERE elevator_id=? AND attempt_id=? AND result='COMPLIANT'",
                Integer.class, e1, attemptId);
        assertEquals(1, records, "应登记且仅登记一条有效维保记录");
        assertEquals(LocalDate.of(2026, 1, 15).toString(), elevator(e1).text("lastMaintenanceDay"));
        assertEquals("RUNNING", elevator(e1).text("status"));

        // 终结后不得再为同一义务派发
        Resp reDispatch = planDispatch("PL-redispatch", e1, obId, List.of(s1, s2), twoItems(),
                W_START, W_END, STARTED);
        assertEquals(422, reDispatch.status());
        assertEquals("OBLIGATION_FULFILLED", reDispatch.text("error"));
    }

    @Test
    @DisplayName("派发前置校验：未到窗、无义务、单人、证书失效、非半开窗、窗边界、进行中救援、重复尝试")
    void dispatchPreconditions() throws Exception {
        long e1 = createElevator("PL-PRE1", COMMISSIONED);
        long e3 = createElevator("PL-PRE3", COMMISSIONED);
        long s1 = createStaff("C-PRE-1", true);
        long s2 = createStaff("C-PRE-2", true);
        long s3 = createStaff("C-PRE-3", false);
        planScan("PL-pre-scan", DISPATCHABLE);
        long obId = firstObligationId(e1);
        long ob3 = firstObligationId(e3);

        // 无义务（未扫描的新电梯）
        long e2 = createElevator("PL-PRE2", COMMISSIONED);
        Resp noObligation = planDispatch("PL-pre-none", e2, null, List.of(s1, s2), twoItems(),
                W_START, W_END, STARTED);
        assertEquals(404, noObligation.status());

        // 未到 24 小时起点（作业窗本身合法，但开始时刻早于最早可派发时刻）
        Resp early = planDispatch("PL-pre-early", e1, obId, List.of(s1, s2), twoItems(),
                DISPATCHABLE.minusSeconds(3600), W_END, DISPATCHABLE.minusSeconds(1));
        assertEquals(422, early.status());
        assertEquals("NOT_DISPATCHABLE", early.text("error"));

        // 单人
        Resp single = planDispatch("PL-pre-single", e1, obId, List.of(s1), twoItems(), W_START, W_END, STARTED);
        assertEquals(400, single.status());
        assertEquals("INVALID_STAFF_SET", single.text("error"));

        // 含未持证人员
        Resp uncertified = planDispatch("PL-pre-uncert", e1, obId, List.of(s1, s3), twoItems(),
                W_START, W_END, STARTED);
        assertEquals(422, uncertified.status());
        assertEquals("UNQUALIFIED_STAFF", uncertified.text("error"));

        // 非半开作业窗 / 起点终点倒置
        Resp badWindow = planDispatch("PL-pre-window", e1, obId, List.of(s1, s2), twoItems(),
                W_END, W_START, STARTED);
        assertEquals(400, badWindow.status());
        assertEquals("INVALID_WINDOW", badWindow.text("error"));

        // 开始时刻恰在终点：不属于半开窗
        Resp atEnd = planDispatch("PL-pre-at-end", e1, obId, List.of(s1, s2), twoItems(),
                W_START, W_END, W_END);
        assertEquals(400, atEnd.status());
        assertEquals("START_OUTSIDE_WINDOW", atEnd.text("error"));

        // 业务失败不占键：同一 requestId 修正为合法参数后应成功
        Resp reused = planDispatch("PL-pre-single", e1, obId, List.of(s1, s2), twoItems(),
                W_START, W_END, STARTED);
        assertEquals(200, reused.status(), "失败不占键，同键合法请求应被受理");
        long attemptId = reused.id();

        // 同一义务已有进行中尝试
        Resp second = planDispatch("PL-pre-second", e1, obId, List.of(s1, s2), twoItems(),
                W_START, W_END, STARTED);
        assertEquals(422, second.status());
        assertEquals("ATTEMPT_IN_FLIGHT", second.text("error"));

        // 同窗人员互斥：另一电梯义务使用重叠窗与 s1 → 整批不占资源
        Resp overlap = planDispatch("PL-pre-overlap", e3, ob3, List.of(s1, s2), twoItems(),
                W_START.plusSeconds(60), W_END, STARTED.plusSeconds(60));
        assertEquals(422, overlap.status());
        assertEquals("STAFF_WINDOW_CONFLICT", overlap.text("error"));
        Integer blockedAttempts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt WHERE elevator_id=?", Integer.class, e3);
        assertEquals(0, blockedAttempts, "任一不足整批不占资源");

        // 完成后人员释放：e1 尝试完成，e3 再用同窗同双人可派发
        assertEquals(200, reportItem(attemptId, "PL-pre-i1", "I-1", true, 1, STARTED.plusSeconds(60)).status());
        assertEquals(200, reportItem(attemptId, "PL-pre-i2", "I-2", true, 2, STARTED.plusSeconds(120)).status());
        assertEquals(200, planComplete(attemptId, "PL-pre-done", List.of(s1, s2), 3, STARTED.plusSeconds(180)).status());
        Resp afterRelease = planDispatch("PL-pre-released", e3, ob3, List.of(s1, s2), twoItems(),
                W_START.plusSeconds(60), W_END, STARTED.plusSeconds(60));
        assertEquals(200, afterRelease.status(), "完成后资源释放，同窗可再派");

        // 进行中救援禁止开始（对已释放义务的下一周期不再适用，改用全新电梯）
        long e4 = createElevator("PL-PRE4", COMMISSIONED);
        planScan("PL-pre-scan4", DISPATCHABLE);
        long ob4 = firstObligationId(e4);
        assertEquals(200, report("PL-pre-rescue", e4, STARTED).status());
        Resp rescueBlock = planDispatch("PL-pre-rescue-dispatch", e4, ob4, List.of(s1, s2), twoItems(),
                W_START, W_END, STARTED);
        assertEquals(422, rescueBlock.status());
        assertEquals("RESCUE_IN_FLIGHT", rescueBlock.text("error"));
    }

    @Test
    @DisplayName("对象不存在404：义务、尝试、清单项、人员与电梯查询")
    void notFoundBranches() throws Exception {
        long e1 = createElevator("PL-NF", COMMISSIONED);
        assertEquals(404, getJson("/api/maintenance-plans/obligations/9999").status());
        assertEquals(404, getJson("/api/maintenance-plans/attempts/9999").status());
        assertEquals(404, getJson("/api/maintenance-plans?elevatorId=9999").status());
        planScan("PL-nf-scan", DISPATCHABLE);
        long obId = firstObligationId(e1);
        Resp r = planDispatch("PL-nf-staff", e1, obId, List.of(12345L, 12346L), twoItems(),
                W_START, W_END, STARTED);
        assertEquals(404, r.status(), "不存在人员应 404");
        long s1 = createStaff("C-NF-1", true);
        long s2 = createStaff("C-NF-2", true);
        long attemptId = dispatchAttempt(e1, obId, s1, s2, twoItems(), "PL-nf-attempt");
        Resp badItem = reportItem(attemptId, "PL-nf-item", "NOPE", true, 1, STARTED.plusSeconds(60));
        assertEquals(404, badItem.status());
    }

    @Test
    @DisplayName("查询：义务列表、尝试列表、尝试轨迹")
    void obligationAndAttemptQueries() throws Exception {
        long e1 = createElevator("PL-Q", COMMISSIONED);
        long s1 = createStaff("C-Q-1", true);
        long s2 = createStaff("C-Q-2", true);
        planScan("PL-q-scan", DISPATCHABLE);
        long obId = firstObligationId(e1);
        long attemptId = dispatchAttempt(e1, obId, s1, s2,
                List.of(new MaintenanceItemInput("I-1", "含=分隔|符 与空格")), "PL-q-attempt");
        assertEquals(200, reportItem(attemptId, "PL-q-i1", "I-1", true, 1, STARTED.plusSeconds(60)).status());

        Resp attempts = planAttemptsOf(obId);
        assertEquals(200, attempts.status());
        assertEquals(1, attempts.body().size());
        assertEquals("含=分隔|符 与空格", attempts.body().get(0).get("items").get(0).get("name").asText(),
                "含分隔符的合法文本须原样区分");

        Resp timeline = planTimeline(attemptId);
        assertEquals(200, timeline.status());
        List<String> events = timeline.body().findValuesAsText("event");
        assertTrue(events.contains("CREATED"));
        assertTrue(events.contains("ITEM_DONE"));
        assertNotNull(planAttempt(attemptId).text("windowStart"));
        assertTrue(planAttempt(attemptId).body().get("rescueId").isNull(), "未被救援抢占时 rescueId 为 null");
        com.fasterxml.jackson.databind.node.ArrayNode arr = (com.fasterxml.jackson.databind.node.ArrayNode)
                planAttempt(attemptId).body().get("assignedStaffIds");
        assertEquals(s1, arr.get(0).asLong());
        assertEquals(s2, arr.get(1).asLong());
    }
}
