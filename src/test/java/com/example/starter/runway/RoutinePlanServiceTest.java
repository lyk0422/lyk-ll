package com.example.starter.runway;

import com.example.starter.runway.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 例行跑道巡检计划服务测试：日历版本化、计划冻结/冲突、开始重确认、紧急抢占、
 * 迟到完成不开放跑道、跨跑道并发与幂等边界（H2 MODE=MySQL 真实事务/唯一约束）。
 */
@SpringBootTest
class RoutinePlanServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-27T02:00:00Z");

    @Autowired
    private RoutinePlanService planService;

    @Autowired
    private RunwayService runwayService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM plan_completion");
        jdbc.update("DELETE FROM plan_check_item");
        jdbc.update("DELETE FROM inspection_plan");
        jdbc.update("DELETE FROM runway_inspection_occupancy");
        jdbc.update("DELETE FROM runway_coordination_lock");
        jdbc.update("DELETE FROM flight_window");
        jdbc.update("DELETE FROM flight_calendar");
        jdbc.update("DELETE FROM notam_record");
        jdbc.update("DELETE FROM assessment_record");
        jdbc.update("DELETE FROM inspection_record");
        jdbc.update("DELETE FROM runway_event");
        jdbc.update("DELETE FROM weather_reading");
    }

    private CalendarPublishResponse publishCalendar(String requestId, int expectedVersion,
                                                     List<FlightOccupancyInput> windows) {
        return planService.publishCalendar(new CalendarPublishRequest(requestId, expectedVersion,
                T0.plus(Duration.ofMinutes(1)), windows));
    }

    private CalendarPublishResponse emptyCalendar(String requestId, int expectedVersion) {
        // 合成日历至少携带一项；用远离各跑道窗口的占用窗代表“无影响航班”
        return publishCalendar(requestId, expectedVersion, List.of(
                new FlightOccupancyInput("RWNONE", "NX-001", T0.plus(Duration.ofHours(10)),
                        T0.plus(Duration.ofHours(11)))));
    }

    private PlanResponse freezePlan(String requestId, String runway, int calendarVersion,
                                    Instant windowStart, Instant windowEnd,
                                    Instant plannedStart, int durationMinutes) {
        return planService.publishPlan(new PlanPublishRequest(requestId, runway, windowStart, windowEnd,
                plannedStart, durationMinutes, calendarVersion));
    }

    private PlanResponse standardFrozenPlan(String requestId, String runway, int calendarVersion) {
        return freezePlan(requestId, runway, calendarVersion, T0, T0.plus(Duration.ofMinutes(60)),
                T0.plus(Duration.ofMinutes(10)), 20);
    }

    // ============================== 日历发布与版本 ==============================

    @Test
    void calendarVersionsStartAtOneAndAdvanceByOne() {
        CalendarPublishResponse v1 = emptyCalendar("cal-1", 0);
        assertEquals(1, v1.version());
        assertEquals(1, v1.windowCount());
        CalendarPublishResponse v2 = emptyCalendar("cal-2", 1);
        assertEquals(2, v2.version());
        Integer windowsV1 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flight_window WHERE calendar_version = 1", Integer.class);
        Integer windowsV2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flight_window WHERE calendar_version = 2", Integer.class);
        assertEquals(1, windowsV1, "旧版本快照须冻结保留");
        assertEquals(1, windowsV2);
    }

    @Test
    void calendarPublishWrongBaseReturns409AndIdempotencyReplay() {
        CalendarPublishResponse first = emptyCalendar("cal-idem", 0);
        assertEquals(1, first.version());
        ApiException stale = assertThrows(ApiException.class, () -> emptyCalendar("cal-stale", 0));
        assertEquals("CALENDAR_VERSION_CONFLICT", stale.getCode());

        ApiException wrong = assertThrows(ApiException.class, () -> emptyCalendar("cal-wrong", 5));
        assertEquals("CALENDAR_VERSION_CONFLICT", wrong.getCode());

        // 同键同参重放首次冻结结果，不再次推进版本
        CalendarPublishResponse replay = emptyCalendar("cal-idem", 0);
        assertEquals(1, replay.version());
        assertEquals(first.publishedAt(), replay.publishedAt());
        Integer version = jdbc.queryForObject("SELECT version FROM flight_calendar WHERE id = 1", Integer.class);
        assertEquals(1, version, "重放不得推进日历版本");
    }

    @Test
    void sameCalendarRequestIdDifferentParamsReturns409AndFailureDoesNotOccupyKey() {
        CalendarPublishRequest first = new CalendarPublishRequest("cal-key", 0,
                T0.plus(Duration.ofMinutes(1)), List.of(new FlightOccupancyInput("RW01", "CA-1",
                T0.plus(Duration.ofMinutes(30)), T0.plus(Duration.ofMinutes(40)))));
        planService.publishCalendar(first);
        CalendarPublishRequest different = new CalendarPublishRequest("cal-key", 0,
                T0.plus(Duration.ofMinutes(2)), List.of(new FlightOccupancyInput("RW01", "CA-1",
                T0.plus(Duration.ofMinutes(30)), T0.plus(Duration.ofMinutes(41)))));
        ApiException conflict = assertThrows(ApiException.class, () -> planService.publishCalendar(different));
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());

        // 业务失败不占键：被拒的新键随后以合法参数可成功
        ApiException badBase = assertThrows(ApiException.class, () -> emptyCalendar("cal-fresh", 9));
        assertEquals("CALENDAR_VERSION_CONFLICT", badBase.getCode());
        assertDoesNotThrow(() -> emptyCalendar("cal-fresh", 1),
                "失败请求不得占用 requestId，同键以正确参数重试应成功");
    }

    @Test
    void nonPositiveFlightWindowReturns400() {
        FlightOccupancyInput bad = new FlightOccupancyInput("RW01", "CA-0",
                T0.plus(Duration.ofMinutes(10)), T0.plus(Duration.ofMinutes(10)));
        ApiException ex = assertThrows(ApiException.class,
                () -> publishCalendar("cal-bad", 0, List.of(bad)));
        assertEquals(400, ex.getStatus().value());
        assertEquals("INVALID_OCCUPANCY_WINDOW", ex.getCode());
        Integer calendarRows = jdbc.queryForObject("SELECT COUNT(*) FROM flight_calendar", Integer.class);
        assertEquals(0, calendarRows, "失败不得产生日历版本");
    }

    @Test
    void calendarChangeMovesFrozenPlansToReviewPendingButNotRunningPlans() {
        emptyCalendar("cal-a", 0);
        PlanResponse frozen = standardFrozenPlan("plan-a", "RW10", 1);
        PlanResponse running = standardFrozenPlan("plan-b", "RW11", 1);
        startPlan(running.id(), 1, T0.plus(Duration.ofMinutes(10)));

        CalendarPublishResponse v2 = emptyCalendar("cal-b", 1);
        assertEquals(List.of(frozen.id()), v2.impactedPlanIds());

        PlanResponse afterFrozen = planService.getPlan(frozen.id());
        assertEquals(PlanStatus.REVIEW_PENDING.name(), afterFrozen.status());
        assertEquals(2, afterFrozen.version(), "待重审是一次成功状态变更，版本加1");
        PlanResponse afterRunning = planService.getPlan(running.id());
        assertEquals(PlanStatus.RUNNING.name(), afterRunning.status(), "已开始巡检不得被自动平移或置重审");
        assertEquals(1, afterRunning.frozenCalendarVersion(), "运行中计划保留原冻结版本");

        CalendarImpactResponse impact = planService.getCalendarImpact();
        assertEquals(2, impact.calendarVersion());
        assertEquals(1, impact.impactedPlans().size());
        assertEquals(frozen.id(), impact.impactedPlans().get(0).planId());
    }

    // ============================== 计划发布与冲突 ==============================

    @Test
    void inspectionFullyInsideWindowFreezesEvidenceAndVersionOne() {
        publishCalendar("cal-p", 0, List.of(
                new FlightOccupancyInput("RW20", "CA-9", T0.plus(Duration.ofMinutes(40)),
                        T0.plus(Duration.ofMinutes(50)))));
        PlanResponse plan = freezePlan("plan-p", "RW20", 1, T0, T0.plus(Duration.ofMinutes(60)),
                T0.plus(Duration.ofMinutes(10)), 20);
        assertEquals(PlanStatus.FROZEN.name(), plan.status());
        assertEquals(1, plan.version());
        assertEquals(1, plan.frozenCalendarVersion());
        assertNotNull(plan.evidence());
        assertEquals(1, plan.evidence().checkedWindows().size(), "证据须覆盖该跑道全部受检航班窗");
        assertTrue(plan.evidence().conflictingFlightNos().isEmpty());
        assertEquals("CA-9", plan.evidence().checkedWindows().get(0).flightNo());
        assertEquals(T0.plus(Duration.ofMinutes(30)), plan.plannedEndAt());
    }

    @Test
    void positiveLengthOverlapRejectedAndEndpointsTouchAreLegal() {
        // RW21 航班窗 [00:30,00:40)：巡检 [00:10,00:30) 端点相接合法；[00:25,00:31) 正长度相交拒绝
        // RW21B 航班窗 [00:05,00:10)：巡检 [00:10,00:15) 在航班终点相接合法
        publishCalendar("cal-o", 0, List.of(
                new FlightOccupancyInput("RW21", "CA-T", T0.plus(Duration.ofMinutes(30)),
                        T0.plus(Duration.ofMinutes(40))),
                new FlightOccupancyInput("RW21B", "CA-S", T0.plus(Duration.ofMinutes(5)),
                        T0.plus(Duration.ofMinutes(10)))));
        PlanResponse touching = freezePlan("plan-touch", "RW21", 1, T0, T0.plus(Duration.ofMinutes(60)),
                T0.plus(Duration.ofMinutes(10)), 20);
        assertEquals(PlanStatus.FROZEN.name(), touching.status());

        PlanResponse touchStart = freezePlan("plan-touch-2", "RW21B", 1, T0, T0.plus(Duration.ofMinutes(60)),
                T0.plus(Duration.ofMinutes(10)), 5);
        assertEquals(PlanStatus.FROZEN.name(), touchStart.status());

        // 不同窗口（避开同跑道同窗唯一名额）内与航班窗正长度相交：拒绝
        ApiException overlap = assertThrows(ApiException.class, () -> freezePlan("plan-overlap", "RW21", 1,
                T0.plus(Duration.ofMinutes(25)), T0.plus(Duration.ofMinutes(45)),
                T0.plus(Duration.ofMinutes(25)), 6));
        assertEquals("FLIGHT_OCCUPANCY_CONFLICT", overlap.getCode());
        assertEquals(422, overlap.getStatus().value());
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM inspection_plan WHERE runway_id = 'RW21'",
                Integer.class);
        assertEquals(1, rows, "冲突计划不得入库（仅端点相接的那一个）");
    }

    @Test
    void inspectionNotWithinWindowRejected() {
        emptyCalendar("cal-w", 0);
        ApiException endBeyond = assertThrows(ApiException.class, () -> freezePlan("plan-beyond", "RW22", 1,
                T0, T0.plus(Duration.ofMinutes(30)), T0.plus(Duration.ofMinutes(20)), 20));
        assertEquals("INSPECTION_NOT_WITHIN_WINDOW", endBeyond.getCode());
        ApiException startBefore = assertThrows(ApiException.class, () -> freezePlan("plan-before", "RW22", 1,
                T0.plus(Duration.ofMinutes(10)), T0.plus(Duration.ofMinutes(60)), T0.plus(Duration.ofMinutes(9)),
                5));
        assertEquals("INSPECTION_NOT_WITHIN_WINDOW", startBefore.getCode());
    }

    @Test
    void sameRunwaySameWindowOnlyOneApprovedPlanButDifferentRunwaysAndWindowsAreFine() {
        emptyCalendar("cal-s", 0);
        standardFrozenPlan("plan-1", "RW23", 1);
        ApiException dup = assertThrows(ApiException.class, () -> standardFrozenPlan("plan-2", "RW23", 1));
        assertEquals("PLAN_WINDOW_OCCUPIED", dup.getCode());

        PlanResponse otherRunway = standardFrozenPlan("plan-3", "RW24", 1);
        assertEquals(PlanStatus.FROZEN.name(), otherRunway.status());
        PlanResponse otherWindow = freezePlan("plan-4", "RW23", 1,
                T0.plus(Duration.ofHours(2)), T0.plus(Duration.ofHours(3)),
                T0.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(5)), 20);
        assertEquals(PlanStatus.FROZEN.name(), otherWindow.status());
    }

    // ============================== 开始重确认 ==============================

    private PlanStartResponse startPlan(long planId, int expectedVersion, Instant startedAt) {
        return planService.startPlan(planId, new PlanStartRequest("start-" + planId + "-" + expectedVersion,
                expectedVersion, startedAt));
    }

    @Test
    void startRequiresCurrentCalendarVersionAndActiveEmergencyBlocksStart() {
        emptyCalendar("cal-st", 0);
        PlanResponse plan = standardFrozenPlan("plan-st", "RW30", 1);

        // 日历推进后未开始计划已置待重审：开始必须先重新审查，不得按旧冻结版本开始
        emptyCalendar("cal-st-2", 1);
        ApiException reviewRequired = assertThrows(ApiException.class,
                () -> startPlan(plan.id(), 2, T0.plus(Duration.ofMinutes(10))));
        assertEquals("PLAN_REVIEW_REQUIRED", reviewRequired.getCode());

        // 重审通过（当前版本2）后计划回到 FROZEN；随后接报紧急关闭把它取消
        PlanResponse reviewed = planService.reviewPlan(plan.id(),
                new PlanReviewRequest("review-st", 2, 2));
        assertEquals(PlanStatus.FROZEN.name(), reviewed.status());
        assertEquals(2, reviewed.frozenCalendarVersion());
        assertEquals(3, reviewed.version());

        runwayService.report(new ReportRequest("emg-st", "RW30", EventType.FOD,
                T0.plus(Duration.ofMinutes(11))));
        // 紧急接报已把该计划取消（FROZEN→CANCELLED）
        PlanResponse cancelled = planService.getPlan(plan.id());
        assertEquals(PlanStatus.CANCELLED.name(), cancelled.status());
    }

    @Test
    void startBlockedByActiveEmergencyWhenPlanStillFrozen() {
        emptyCalendar("cal-se", 0);
        // 新计划位于另一窗口，紧急事件先发生在同跑道（计划在紧急之后发布无业务意义，
        // 因此这里先制造进行中紧急事件，再尝试开始一个历史窗口外的新冻结计划）
        runwayService.report(new ReportRequest("emg-se", "RW31", EventType.FOD, T0.plus(Duration.ofMinutes(0))));
        // 紧急接报会取消同跑道所有未开始计划；构造一个紧急事件发生后才申请的窗口（更晚窗口）
        PlanResponse plan = freezePlan("plan-se", "RW31", 1,
                T0.plus(Duration.ofHours(3)), T0.plus(Duration.ofHours(4)),
                T0.plus(Duration.ofHours(3)).plus(Duration.ofMinutes(5)), 20);
        ApiException blocked = assertThrows(ApiException.class,
                () -> startPlan(plan.id(), 1, T0.plus(Duration.ofHours(3)).plus(Duration.ofMinutes(5))));
        assertEquals("RUNWAY_EMERGENCY_ACTIVE", blocked.getCode());
        Integer occupancy = jdbc.queryForObject(
                "SELECT COUNT(*) FROM runway_inspection_occupancy WHERE runway_id = 'RW31'", Integer.class);
        assertEquals(0, occupancy, "被拒开始不得留下跑道占用");
    }

    @Test
    void startSuccessTransitionsRunningCreatesOccupancyAndBumpsVersion() {
        emptyCalendar("cal-go", 0);
        PlanResponse plan = standardFrozenPlan("plan-go", "RW32", 1);
        PlanStartResponse started = startPlan(plan.id(), 1, T0.plus(Duration.ofMinutes(10)));
        assertEquals(PlanStatus.RUNNING.name(), started.status());
        assertEquals(2, started.version());
        assertEquals(1, started.calendarVersion());
        Integer occupancy = jdbc.queryForObject(
                "SELECT COUNT(*) FROM runway_inspection_occupancy WHERE runway_id = 'RW32' AND plan_id = ?",
                Integer.class, plan.id());
        assertEquals(1, occupancy);
    }

    @Test
    void secondRunningPlanOnSameRunwayRejectedByUniqueOccupancyAndRolledBack() {
        emptyCalendar("cal-occ", 0);
        PlanResponse first = standardFrozenPlan("plan-occ-1", "RW33", 1);
        startPlan(first.id(), 1, T0.plus(Duration.ofMinutes(10)));
        PlanResponse second = freezePlan("plan-occ-2", "RW33", 1,
                T0.plus(Duration.ofHours(2)), T0.plus(Duration.ofHours(3)),
                T0.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(5)), 20);
        ApiException blocked = assertThrows(ApiException.class,
                () -> startPlan(second.id(), 1, T0.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(5))));
        assertEquals("RUNWAY_INSPECTION_OCCUPIED", blocked.getCode());
        assertEquals(PlanStatus.FROZEN.name(), planService.getPlan(second.id()).status(),
                "占用冲突须回滚状态变更，计划仍为 FROZEN 且版本不变");
        assertEquals(1, planService.getPlan(second.id()).version());
        Integer occupancy = jdbc.queryForObject(
                "SELECT COUNT(*) FROM runway_inspection_occupancy WHERE runway_id = 'RW33'", Integer.class);
        assertEquals(1, occupancy, "同跑道至多一个进行中例行巡检");
    }

    // ============================== 紧急抢占与迟到完成 ==============================

    @Test
    void emergencyAbortsRunningPlanPreservesChecksAndLateCompletionDoesNotReopen() {
        emptyCalendar("cal-em", 0);
        PlanResponse plan = standardFrozenPlan("plan-em", "RW40", 1);
        PlanStartResponse started = startPlan(plan.id(), 1, T0.plus(Duration.ofMinutes(10)));

        PlanCheckResponse check1 = planService.reportCheck(plan.id(),
                new PlanCheckItemRequest("chk-1", started.version(), "LIGHTING", CheckResult.PASS,
                        "正常|含分隔符/也须原样保留", T0.plus(Duration.ofMinutes(12))));
        assertEquals(3, check1.version());
        planService.reportCheck(plan.id(),
                new PlanCheckItemRequest("chk-2", 3, "FOD-SWEEP", CheckResult.FAIL, null,
                        T0.plus(Duration.ofMinutes(14))));

        // 巡检进行中接报紧急关闭
        ReportResponse emergency = runwayService.report(new ReportRequest("emg-1", "RW40", EventType.FOD,
                T0.plus(Duration.ofMinutes(15))));

        PlanResponse aborted = planService.getPlan(plan.id());
        assertEquals(PlanStatus.ABORTED.name(), aborted.status());
        assertEquals(emergenceId(emergency), aborted.abortedByEventId());
        assertEquals(2, aborted.checks().size(), "已完成检查项必须保留");
        assertEquals("正常|含分隔符/也须原样保留", aborted.checks().get(0).remark(),
                "含分隔符的合法文本按结构化字段原样保存");
        assertEquals(5, aborted.version(), "中止为一次成功状态变更：1→2开始→3检查→4检查→5中止");

        Integer occupancy = jdbc.queryForObject(
                "SELECT COUNT(*) FROM runway_inspection_occupancy WHERE runway_id = 'RW40'", Integer.class);
        assertEquals(0, occupancy, "中止须释放巡检占用");

        // 迟到完成回报：只登记，不开放跑道
        int versionBefore = aborted.version();
        PlanCompleteResponse late1 = planService.completePlan(plan.id(),
                new PlanCompleteRequest("done-late-1", versionBefore, T0.plus(Duration.ofMinutes(25))));
        assertTrue(late1.late());
        assertEquals(PlanStatus.ABORTED.name(), late1.status());
        assertEquals(versionBefore, late1.version(), "迟到登记不得变更计划版本");
        PlanCompleteResponse late2 = planService.completePlan(plan.id(),
                new PlanCompleteRequest("done-late-2", versionBefore, T0.plus(Duration.ofMinutes(26))));
        assertTrue(late2.late());

        PlanResponse afterLate = planService.getPlan(plan.id());
        assertEquals(PlanStatus.ABORTED.name(), afterLate.status(), "迟到回报不得开放跑道或改变状态");
        assertEquals(2, afterLate.lateCompletionCount());
        assertEquals(2, afterLate.completionCount());
        String emergencyStatus = jdbc.queryForObject(
                "SELECT status FROM runway_event WHERE id = ?", String.class, emergency.eventId());
        assertEquals("CLOSED", emergencyStatus, "例行迟到回报不得开放跑道/改变紧急事件状态");
        Integer reopenNotams = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notam_record WHERE event_id = ? AND action = 'REOPEN'", Integer.class,
                emergency.eventId());
        assertEquals(0, reopenNotams, "迟到回报不得触发恢复通告");
    }

    @Test
    void emergencyCancelsFrozenPlansAndDoesNotReviveAfterEmergencyEnds() {
        emptyCalendar("cal-cx", 0);
        PlanResponse frozenA = standardFrozenPlan("plan-cx-a", "RW41", 1);
        PlanResponse frozenB = freezePlan("plan-cx-b", "RW41", 1,
                T0.plus(Duration.ofHours(2)), T0.plus(Duration.ofHours(3)),
                T0.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(5)), 20);

        ReportResponse emergency = runwayService.report(new ReportRequest("emg-cx", "RW41", EventType.FOD,
                T0.plus(Duration.ofMinutes(5))));
        assertEquals(PlanStatus.CANCELLED.name(), planService.getPlan(frozenA.id()).status());
        assertEquals(PlanStatus.CANCELLED.name(), planService.getPlan(frozenB.id()).status());
        assertNull(planService.getPlan(frozenA.id()).abortedByEventId(), "取消不记录中止事件，仅中止记录");

        // 紧急事件结束（旧流程恢复开放）后：只能另建新计划，被取消项不复活
        runwayService.startInspection(emergency.eventId(),
                new InspectionRequest(T0.plus(Duration.ofMinutes(6)), 2));
        runwayService.assess(emergency.eventId(), new AssessmentRequest("a-cx",
                new java.math.BigDecimal("0.80"), AssessmentType.CLEAR_TO_REOPEN,
                T0.plus(Duration.ofMinutes(8))));
        runwayService.reopen(emergency.eventId(),
                new ReopenRequest("r-cx", T0.plus(Duration.ofMinutes(9))));

        PlanResponse renewed = standardFrozenPlan("plan-new", "RW41", 1);
        PlanStartResponse started = startPlan(renewed.id(), 1, T0.plus(Duration.ofMinutes(10)));
        assertEquals(PlanStatus.RUNNING.name(), started.status());
        assertEquals(PlanStatus.CANCELLED.name(), planService.getPlan(frozenA.id()).status(),
                "被抢占计划不得复活");
    }

    @Test
    void normalCompletionOnlyClosesPlanAndReleasesOccupancy() {
        emptyCalendar("cal-done", 0);
        PlanResponse plan = standardFrozenPlan("plan-done", "RW42", 1);
        startPlan(plan.id(), 1, T0.plus(Duration.ofMinutes(10)));
        PlanCompleteResponse done = planService.completePlan(plan.id(),
                new PlanCompleteRequest("done-ok", 2, T0.plus(Duration.ofMinutes(28))));
        assertFalse(done.late());
        assertEquals(PlanStatus.COMPLETED.name(), done.status());
        assertEquals(3, done.version());
        Integer occupancy = jdbc.queryForObject(
                "SELECT COUNT(*) FROM runway_inspection_occupancy WHERE runway_id = 'RW42'", Integer.class);
        assertEquals(0, occupancy);
        Integer events = jdbc.queryForObject("SELECT COUNT(*) FROM runway_event WHERE runway_id = 'RW42'",
                Integer.class);
        assertEquals(0, events, "例行正常完成不应自行创建紧急事件或开放流程");
    }

    // ============================== 幂等边界 ==============================

    @Test
    void planWritesAreIdempotentAndDelimiterBearingRemarksAreDistinguished() {
        emptyCalendar("cal-id", 0);
        PlanResponse plan = standardFrozenPlan("plan-id", "RW50", 1);
        PlanStartRequest startReq = new PlanStartRequest("start-id", 1, T0.plus(Duration.ofMinutes(10)));
        PlanStartResponse s1 = planService.startPlan(plan.id(), startReq);
        PlanStartResponse s2 = planService.startPlan(plan.id(), startReq);
        assertEquals(s1.version(), s2.version());
        assertEquals(2, s2.version());

        PlanCheckItemRequest checkReq = new PlanCheckItemRequest("chk-id", 2, "MARKING", CheckResult.PASS,
                "a|b", T0.plus(Duration.ofMinutes(12)));
        PlanCheckResponse c1 = planService.reportCheck(plan.id(), checkReq);
        PlanCheckResponse c2 = planService.reportCheck(plan.id(), checkReq);
        assertEquals(c1.itemId(), c2.itemId());
        Integer checkRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_check_item WHERE request_id = 'chk-id'", Integer.class);
        assertEquals(1, checkRows, "同键同参重放不重复落检查项");

        // 仅备注内容不同（含分隔符的拼接歧义场景）须判异参 409
        PlanCheckItemRequest checkDiff = new PlanCheckItemRequest("chk-id", 2, "MARKING", CheckResult.PASS,
                "a", T0.plus(Duration.ofMinutes(12)));
        ApiException conflict = assertThrows(ApiException.class,
                () -> planService.reportCheck(plan.id(), checkDiff));
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());

        // ["a|","b"] 与 ["a","|b"] 这类分隔符拼接歧义也必须区分
        PlanCheckItemRequest splitOne = new PlanCheckItemRequest("chk-split", 3, "X", CheckResult.PASS,
                "a|", T0.plus(Duration.ofMinutes(13)));
        PlanCheckItemRequest splitTwo = new PlanCheckItemRequest("chk-split", 3, "X", CheckResult.PASS,
                "a", T0.plus(Duration.ofMinutes(13)));
        // 第二项以 itemCode 携带 "|b" 构造真正的跨字段拼接歧义
        PlanCheckItemRequest splitAlt = new PlanCheckItemRequest("chk-split", 3, "X|b", CheckResult.PASS,
                "a", T0.plus(Duration.ofMinutes(13)));
        planService.reportCheck(plan.id(), splitOne);
        assertThrows(ApiException.class, () -> planService.reportCheck(plan.id(), splitTwo));
        assertThrows(ApiException.class, () -> planService.reportCheck(plan.id(), splitAlt));
    }

    @Test
    void lateCompletionSameKeyReplaysAndNewKeysRegisterAgain() {
        emptyCalendar("cal-lc", 0);
        PlanResponse plan = standardFrozenPlan("plan-lc", "RW51", 1);
        startPlan(plan.id(), 1, T0.plus(Duration.ofMinutes(10)));
        runwayService.report(new ReportRequest("emg-lc", "RW51", EventType.FOD, T0.plus(Duration.ofMinutes(15))));
        int abortedVersion = planService.getPlan(plan.id()).version();

        PlanCompleteRequest req = new PlanCompleteRequest("late-idem", abortedVersion,
                T0.plus(Duration.ofMinutes(25)));
        PlanCompleteResponse r1 = planService.completePlan(plan.id(), req);
        PlanCompleteResponse r2 = planService.completePlan(plan.id(), req);
        assertTrue(r1.late() && r2.late());
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_completion WHERE request_id = 'late-idem'", Integer.class);
        assertEquals(1, rows, "同键同参迟到回报重放不重复登记");
    }

    // ============================== 404/状态机失败分支 ==============================

    @Test
    void unknownPlanReturns404() {
        ApiException ex = assertThrows(ApiException.class, () -> planService.getPlan(999999L));
        assertEquals(404, ex.getStatus().value());
        assertEquals("PLAN_NOT_FOUND", ex.getCode());
    }

    @Test
    void checksAndCompletionOnlyAcceptedInRunningState() {
        emptyCalendar("cal-st2", 0);
        PlanResponse plan = standardFrozenPlan("plan-st2", "RW52", 1);
        ApiException notRunning = assertThrows(ApiException.class, () -> planService.reportCheck(plan.id(),
                new PlanCheckItemRequest("chk-x", 1, "LIGHTING", CheckResult.PASS, null,
                        T0.plus(Duration.ofMinutes(11)))));
        assertEquals("PLAN_NOT_RUNNING", notRunning.getCode());
        ApiException noComplete = assertThrows(ApiException.class, () -> planService.completePlan(plan.id(),
                new PlanCompleteRequest("done-x", 1, T0.plus(Duration.ofMinutes(20)))));
        assertEquals("PLAN_NOT_RUNNING", noComplete.getCode());
    }

    @Test
    void staleOptimisticVersionReturns409() {
        emptyCalendar("cal-opt", 0);
        PlanResponse plan = standardFrozenPlan("plan-opt", "RW53", 1);
        startPlan(plan.id(), 1, T0.plus(Duration.ofMinutes(10)));
        ApiException ex = assertThrows(ApiException.class, () -> planService.reportCheck(plan.id(),
                new PlanCheckItemRequest("chk-opt", 1, "LIGHTING", CheckResult.PASS, null,
                        T0.plus(Duration.ofMinutes(12)))));
        assertEquals("PLAN_VERSION_CONFLICT", ex.getCode());
        assertEquals(409, ex.getStatus().value());
    }

    // ============================== 并发：开始 vs 紧急接报 ==============================

    @Test
    void concurrentStartAndEmergencyReachUniqueOccupancy() throws Exception {
        emptyCalendar("cal-race", 0);
        PlanResponse plan = standardFrozenPlan("plan-race", "RW60", 1);

        int iterations = 6;
        for (int iter = 0; iter < iterations; iter++) {
            final int i = iter;
            long planId = plan.id();
            // 每个迭代重置：若计划被取消/中止则重新发布新计划，确保两者真正并发竞争
            String state = planService.getPlan(planId).status();
            if (!PlanStatus.FROZEN.name().equals(state)) {
                planId = standardFrozenPlan("plan-race-" + i, "RW60", 1).id();
            }
            final long competingPlanId = planId;
            int threads = 2;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch startGate = new CountDownLatch(1);
            AtomicReference<Throwable> startError = new AtomicReference<>();
            AtomicReference<Throwable> emergencyError = new AtomicReference<>();

            Future<?> f1 = pool.submit(() -> {
                ready.countDown();
                try {
                    startGate.await();
                    planService.startPlan(competingPlanId,
                            new PlanStartRequest("race-start-" + i, 1, T0.plus(Duration.ofMinutes(10))));
                } catch (Throwable t) {
                    startError.set(t);
                }
            });
            Future<?> f2 = pool.submit(() -> {
                ready.countDown();
                try {
                    startGate.await();
                    runwayService.report(new ReportRequest("race-emg-" + i, "RW60", EventType.FOD,
                            T0.plus(Duration.ofMinutes(10))));
                } catch (Throwable t) {
                    emergencyError.set(t);
                }
            });
            ready.await(5, TimeUnit.SECONDS);
            startGate.countDown();
            f1.get(15, TimeUnit.SECONDS);
            f2.get(15, TimeUnit.SECONDS);
            pool.shutdown();

            // 不变量：同跑道至多一个进行中紧急事件；巡检占用表为空；计划不可能同时 RUNNING 且被紧急接报
            Integer activeEmergencies = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM runway_event WHERE runway_id = 'RW60' AND active_flag = 1",
                    Integer.class);
            assertEquals(1, activeEmergencies, "每次迭代后必须恰有一个进行中紧急事件");
            Integer occupancy = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM runway_inspection_occupancy WHERE runway_id = 'RW60'", Integer.class);
            assertEquals(0, occupancy, "紧急接报后不得残留巡检占用");
            String planStatus = jdbc.queryForObject("SELECT status FROM inspection_plan WHERE id = ?",
                    String.class, competingPlanId);
            assertNotEquals("RUNNING", planStatus, "紧急接报与开始并发后，计划不得仍为 RUNNING");

            // 关闭本轮紧急事件（走完整旧流程释放 active_flag），以便下一轮在同跑道再竞争
            Long emergencyId = jdbc.queryForObject(
                    "SELECT id FROM runway_event WHERE runway_id = 'RW60' AND active_flag = 1", Long.class);
            assertNotNull(emergencyId);
            runwayService.startInspection(emergencyId,
                    new InspectionRequest(T0.plus(Duration.ofMinutes(11)), 2));
            runwayService.assess(emergencyId, new AssessmentRequest("race-a-" + i,
                    new java.math.BigDecimal("0.70"), AssessmentType.CLEAR_TO_REOPEN,
                    T0.plus(Duration.ofMinutes(12))));
            runwayService.reopen(emergencyId,
                    new ReopenRequest("race-r-" + i, T0.plus(Duration.ofMinutes(13))));
        }
    }

    @Test
    void differentRunwaysDoNotBlockEachOther() throws Exception {
        emptyCalendar("cal-parallel", 0);
        PlanResponse planA = standardFrozenPlan("plan-par-a", "RW61", 1);
        PlanResponse planB = standardFrozenPlan("plan-par-b", "RW62", 1);
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Future<?> f1 = pool.submit(() -> {
            ready.countDown();
            try {
                gate.await();
                planService.startPlan(planA.id(), new PlanStartRequest("par-start-a", 1,
                        T0.plus(Duration.ofMinutes(10))));
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            }
        });
        Future<?> f2 = pool.submit(() -> {
            ready.countDown();
            try {
                gate.await();
                runwayService.report(new ReportRequest("par-emg-b", "RW62", EventType.FOD,
                        T0.plus(Duration.ofMinutes(10))));
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            }
        });
        ready.await(5, TimeUnit.SECONDS);
        gate.countDown();
        f1.get(15, TimeUnit.SECONDS);
        f2.get(15, TimeUnit.SECONDS);
        pool.shutdown();
        assertNull(error.get(), "跨跑道操作互不阻塞，均应成功");
        assertEquals(PlanStatus.RUNNING.name(), planService.getPlan(planA.id()).status(),
                "另一跑道紧急事件不影响本跑道巡检");
        assertEquals(PlanStatus.CANCELLED.name(), planService.getPlan(planB.id()).status());
    }

    private long emergenceId(ReportResponse response) {
        return response.eventId();
    }
}
