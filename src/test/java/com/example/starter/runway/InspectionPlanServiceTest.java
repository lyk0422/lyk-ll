package com.example.starter.runway;

import com.example.starter.runway.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
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
 * 例行巡检窗口编排与紧急关闭抢占测试：覆盖主流程、失败分支、并发与幂等边界（真实 H2）。
 */
@SpringBootTest
class InspectionPlanServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-27T00:00:00Z");

    @Autowired
    private InspectionPlanService planService;

    @Autowired
    private RunwayService runwayService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM plan_check_report");
        jdbc.update("DELETE FROM inspection_plan");
        jdbc.update("DELETE FROM flight_calendar");
        jdbc.update("DELETE FROM notam_record");
        jdbc.update("DELETE FROM assessment_record");
        jdbc.update("DELETE FROM inspection_record");
        jdbc.update("DELETE FROM runway_event");
        jdbc.update("DELETE FROM weather_reading");
    }

    private PlanResponse publish(String requestId, String runway, Instant windowStart, Instant windowEnd,
                                 int minutes, List<FlightWindow> flightWindows) {
        return planService.publish(new PlanPublishRequest(requestId, runway, windowStart, windowEnd,
                minutes, flightWindows));
    }

    private PlanResponse publishDefault(String requestId, String runway) {
        return publish(requestId, runway, T0, T0.plus(Duration.ofHours(1)), 30, List.of());
    }

    @Test
    void mainFlowPublishStartReportComplete() {
        PlanResponse plan = publishDefault("p-1", "RW-P1");
        assertEquals("APPROVED", plan.status());
        assertEquals(1, plan.version());
        assertEquals(1, plan.calendarVersion());
        assertTrue(plan.conflictEvidence().contains("PASS"), "发布须冻结冲突证据");

        PlanResponse started = planService.start(plan.planId(),
                new PlanStartRequest("s-1", 1, 1, T0.plus(Duration.ofMinutes(10))));
        assertEquals("IN_PROGRESS", started.status());
        assertEquals(2, started.version());
        assertEquals(T0.plus(Duration.ofMinutes(10)), started.startedAt());

        CheckReportResponse partial = planService.reportCheck(plan.planId(),
                new CheckReportRequest("c-1", 2, T0.plus(Duration.ofMinutes(20)),
                        List.of("道面检查"), false));
        assertFalse(partial.late());
        assertEquals("IN_PROGRESS", partial.planStatus());
        assertEquals(3, partial.version());

        CheckReportResponse completed = planService.reportCheck(plan.planId(),
                new CheckReportRequest("c-2", 3, T0.plus(Duration.ofMinutes(35)),
                        List.of("灯光检查"), true));
        assertEquals("COMPLETED", completed.planStatus());
        assertEquals(4, completed.version());

        PlanResponse detail = planService.getPlan(plan.planId());
        assertEquals("COMPLETED", detail.status());
        assertTrue(detail.completedItems().contains("道面检查"));
        assertTrue(detail.completedItems().contains("灯光检查"));

        // 例行完成只关闭计划：不产生跑道事件与通告
        Integer eventCount = jdbc.queryForObject("SELECT COUNT(*) FROM runway_event", Integer.class);
        Integer notamCount = jdbc.queryForObject("SELECT COUNT(*) FROM notam_record", Integer.class);
        assertEquals(0, eventCount);
        assertEquals(0, notamCount);
    }

    @Test
    void invalidWindowAndFlightWindowReturn400() {
        ApiException inverted = assertThrows(ApiException.class, () -> publish("p-bad-1", "RW-P2",
                T0.plus(Duration.ofHours(1)), T0, 30, List.of()));
        assertEquals("WINDOW_INVALID", inverted.getCode());
        assertEquals(400, inverted.getStatus().value());

        ApiException badFlight = assertThrows(ApiException.class, () -> publish("p-bad-2", "RW-P2",
                T0, T0.plus(Duration.ofHours(1)), 30,
                List.of(new FlightWindow(T0.plus(Duration.ofMinutes(10)), T0.plus(Duration.ofMinutes(5))))));
        assertEquals("FLIGHT_WINDOW_INVALID", badFlight.getCode());
        assertEquals(400, badFlight.getStatus().value());
    }

    @Test
    void durationMustFitInsideWindow() {
        ApiException ex = assertThrows(ApiException.class, () -> publish("p-dur", "RW-P3",
                T0, T0.plus(Duration.ofMinutes(30)), 31, List.of()));
        assertEquals("DURATION_EXCEEDS_WINDOW", ex.getCode());
        assertEquals(422, ex.getStatus().value());

        // 恰满窗口合法
        PlanResponse exact = publish("p-dur-ok", "RW-P3", T0, T0.plus(Duration.ofMinutes(30)), 30, List.of());
        assertEquals("APPROVED", exact.status());
    }

    @Test
    void positiveOverlapRejectedButTouchingEndpointsAllowed() {
        // 正长度相交拒绝
        ApiException overlap = assertThrows(ApiException.class, () -> publish("p-ov", "RW-P4",
                T0, T0.plus(Duration.ofHours(1)), 30,
                List.of(new FlightWindow(T0.plus(Duration.ofMinutes(29)), T0.plus(Duration.ofMinutes(40))))));
        assertEquals("FLIGHT_WINDOW_CONFLICT", overlap.getCode());
        assertEquals(422, overlap.getStatus().value());

        // 端点相接合法：航班占用窗终点恰为巡检起点
        PlanResponse touchingBefore = publish("p-ov-2", "RW-P4", T0, T0.plus(Duration.ofHours(1)), 30,
                List.of(new FlightWindow(T0.minus(Duration.ofMinutes(20)), T0)));
        assertEquals("APPROVED", touchingBefore.status());

        // 端点相接合法：航班占用窗起点恰为巡检终点
        PlanResponse touchingAfter = publish("p-ov-3", "RW-P4B", T0, T0.plus(Duration.ofHours(1)), 30,
                List.of(new FlightWindow(T0.plus(Duration.ofMinutes(30)), T0.plus(Duration.ofMinutes(50)))));
        assertEquals("APPROVED", touchingAfter.status());
    }

    @Test
    void sameRunwaySameWindowAllowsOnlyOneApprovedPlan() {
        publishDefault("p-dup-1", "RW-P5");
        ApiException occupied = assertThrows(ApiException.class, () -> publishDefault("p-dup-2", "RW-P5"));
        assertEquals("PLAN_WINDOW_OCCUPIED", occupied.getCode());
        assertEquals(409, occupied.getStatus().value());

        // 同窗不同跑道互不阻塞
        PlanResponse otherRunway = publishDefault("p-dup-3", "RW-P5B");
        assertEquals("APPROVED", otherRunway.status());

        // 同跑道不同窗口可并存
        PlanResponse otherWindow = publish("p-dup-4", "RW-P5",
                T0.plus(Duration.ofHours(2)), T0.plus(Duration.ofHours(3)), 30, List.of());
        assertEquals("APPROVED", otherWindow.status());
    }

    @Test
    void calendarChangeMarksPendingReviewAndStartMustReconfirm() {
        PlanResponse plan = publishDefault("p-cal-1", "RW-P6");

        CalendarChangeResponse changed = planService.changeCalendar("RW-P6",
                new CalendarChangeRequest("cal-1", 1, T0.plus(Duration.ofMinutes(5))));
        assertEquals(2, changed.version());
        assertEquals(List.of(plan.planId()), changed.affectedPlanIds());

        PlanResponse pending = planService.getPlan(plan.planId());
        assertEquals("PENDING_REVIEW", pending.status());
        assertEquals(2, pending.version(), "待重审转移是一次成功变更，版本加1");
        assertEquals(1, pending.calendarVersion(), "冻结版本保留作为证据");

        // 日历影响查询可见
        CalendarImpactResponse impact = planService.calendarImpact("RW-P6");
        assertEquals(2, impact.currentVersion());
        assertEquals(1, impact.affectedPlans().size());
        assertEquals(plan.planId(), impact.affectedPlans().get(0).planId());
        assertEquals("PENDING_REVIEW", impact.affectedPlans().get(0).status());

        // 以旧日历版本开始被拒绝
        ApiException stale = assertThrows(ApiException.class, () -> planService.start(plan.planId(),
                new PlanStartRequest("s-cal-1", 2, 1, T0.plus(Duration.ofMinutes(10)))));
        assertEquals("CALENDAR_VERSION_MISMATCH", stale.getCode());
        assertEquals(422, stale.getStatus().value());

        // 重新确认当前日历版本后开始，冻结版本重冻结为当前版本
        PlanResponse started = planService.start(plan.planId(),
                new PlanStartRequest("s-cal-2", 2, 2, T0.plus(Duration.ofMinutes(10))));
        assertEquals("IN_PROGRESS", started.status());
        assertEquals(2, started.calendarVersion());

        // 日历再次变更：已开始计划不平移，但影响查询可见
        planService.changeCalendar("RW-P6", new CalendarChangeRequest("cal-2", 2,
                T0.plus(Duration.ofMinutes(15))));
        PlanResponse inProgress = planService.getPlan(plan.planId());
        assertEquals("IN_PROGRESS", inProgress.status());
        assertEquals(2, inProgress.calendarVersion());
        CalendarImpactResponse impact2 = planService.calendarImpact("RW-P6");
        assertEquals(3, impact2.currentVersion());
        assertEquals(1, impact2.affectedPlans().size());
        assertEquals("IN_PROGRESS", impact2.affectedPlans().get(0).status());
    }

    @Test
    void expectedVersionMismatchReturns409() {
        PlanResponse plan = publishDefault("p-ver-1", "RW-P7");
        ApiException ex = assertThrows(ApiException.class, () -> planService.start(plan.planId(),
                new PlanStartRequest("s-ver-1", 99, 1, T0.plus(Duration.ofMinutes(10)))));
        assertEquals("VERSION_CONFLICT", ex.getCode());
        assertEquals(409, ex.getStatus().value());

        ApiException calendarStale = assertThrows(ApiException.class, () -> planService.changeCalendar(
                "RW-P7", new CalendarChangeRequest("cal-ver-1", 7, T0)));
        assertEquals("VERSION_CONFLICT", calendarStale.getCode());
    }

    @Test
    void unknownPlanReturns404() {
        ApiException ex = assertThrows(ApiException.class, () -> planService.getPlan(999999L));
        assertEquals("PLAN_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
    }

    @Test
    void emergencyReportPreemptsPlansAndLateReportOnlyRecorded() {
        PlanResponse notStarted = publishDefault("p-em-1", "RW-P8");
        PlanResponse startedPlan = publish("p-em-2", "RW-P8",
                T0.plus(Duration.ofHours(2)), T0.plus(Duration.ofHours(3)), 30, List.of());
        planService.start(startedPlan.planId(),
                new PlanStartRequest("s-em-1", 1, 1, T0.plus(Duration.ofHours(2))));
        planService.reportCheck(startedPlan.planId(),
                new CheckReportRequest("c-em-1", 2, T0.plus(Duration.ofHours(2).plusMinutes(10)),
                        List.of("道面检查"), false));

        // 紧急接报：同事务抢占该跑道例行计划
        ReportResponse event = runwayService.report(new ReportRequest("ev-em-1", "RW-P8", EventType.FOD,
                T0.plus(Duration.ofHours(2).plusMinutes(15))));

        PlanResponse cancelled = planService.getPlan(notStarted.planId());
        assertEquals("CANCELLED", cancelled.status(), "未开始计划须取消");
        assertEquals(2, cancelled.version());

        PlanResponse aborted = planService.getPlan(startedPlan.planId());
        assertEquals("ABORTED", aborted.status(), "已开始计划须中止");
        assertEquals(4, aborted.version());
        assertTrue(aborted.completedItems().contains("道面检查"), "已完成检查项须保留");

        // 被抢占后的完成回报只记录迟到，不得开放跑道
        CheckReportResponse late = planService.reportCheck(startedPlan.planId(),
                new CheckReportRequest("c-em-2", 4, T0.plus(Duration.ofHours(2).plusMinutes(30)),
                        List.of("灯光检查"), true));
        assertTrue(late.late());
        assertEquals("ABORTED", late.planStatus());
        assertEquals(4, late.version(), "迟到登记不改变计划版本");

        String eventStatus = jdbc.queryForObject("SELECT status FROM runway_event WHERE id = ?",
                String.class, event.eventId());
        Integer reopenNotams = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notam_record WHERE event_id = ? AND action = 'REOPEN'",
                Integer.class, event.eventId());
        assertEquals("CLOSED", eventStatus, "例行回报不得开放跑道");
        assertEquals(0, reopenNotams);

        Integer lateCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_check_report WHERE plan_id = ? AND late = TRUE",
                Integer.class, startedPlan.planId());
        assertEquals(1, lateCount);
    }

    @Test
    void newPlanAllowedAfterEmergencyEndsAndPreemptedNotRevived() {
        PlanResponse preempted = publishDefault("p-end-1", "RW-P9");
        ReportResponse event = runwayService.report(new ReportRequest("ev-end-1", "RW-P9", EventType.FOD, T0));
        assertEquals("CANCELLED", planService.getPlan(preempted.planId()).status());

        // 紧急事件进行中不得发布新计划
        ApiException blocked = assertThrows(ApiException.class, () -> publishDefault("p-end-2", "RW-P9"));
        assertEquals("RUNWAY_EMERGENCY_ACTIVE", blocked.getCode());
        assertEquals(422, blocked.getStatus().value());

        // 紧急事件结束（既有恢复开放流程）后可另建新计划
        runwayService.startInspection(event.eventId(),
                new InspectionRequest(T0.plus(Duration.ofMinutes(3)), 2));
        runwayService.assess(event.eventId(), new AssessmentRequest("a-end-1", new BigDecimal("0.50"),
                AssessmentType.CLEAR_TO_REOPEN, T0.plus(Duration.ofMinutes(6))));
        runwayService.reopen(event.eventId(), new ReopenRequest("r-end-1", T0.plus(Duration.ofMinutes(9))));

        PlanResponse newPlan = publishDefault("p-end-3", "RW-P9");
        assertEquals("APPROVED", newPlan.status(), "紧急事件结束后可另建新计划（同窗可重用）");

        // 被抢占项不复活
        assertEquals("CANCELLED", planService.getPlan(preempted.planId()).status());
    }

    @Test
    void idempotentReplayAndStructuredFingerprint() {
        PlanPublishRequest request = new PlanPublishRequest("idem-p", "RW-P10", T0,
                T0.plus(Duration.ofHours(1)), 30, List.of());
        PlanResponse first = planService.publish(request);
        PlanResponse replay = planService.publish(request);
        assertEquals(first.planId(), replay.planId(), "同键同参重放首次冻结结果");
        Integer planCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inspection_plan WHERE request_id = 'idem-p'", Integer.class);
        assertEquals(1, planCount);

        ApiException conflict = assertThrows(ApiException.class, () -> planService.publish(
                new PlanPublishRequest("idem-p", "RW-P10", T0, T0.plus(Duration.ofHours(2)), 30, List.of())));
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());

        // 失败不占键：业务拒绝后同键修正参数可成功
        ApiException rejected = assertThrows(ApiException.class, () -> publish("idem-f", "RW-P10",
                T0.plus(Duration.ofHours(4)), T0.plus(Duration.ofHours(5)), 30,
                List.of(new FlightWindow(T0.plus(Duration.ofHours(4)),
                        T0.plus(Duration.ofHours(4).plusMinutes(10))))));
        assertEquals("FLIGHT_WINDOW_CONFLICT", rejected.getCode());
        PlanResponse corrected = publish("idem-f", "RW-P10",
                T0.plus(Duration.ofHours(4)), T0.plus(Duration.ofHours(5)), 30, List.of());
        assertEquals("APPROVED", corrected.status());

        // 结构化指纹：集合边界不同（["a","b"] 与 ["a|b"]）不得误判为同参
        planService.start(first.planId(),
                new PlanStartRequest("idem-s", 1, 1, T0.plus(Duration.ofMinutes(5))));
        CheckReportResponse reported = planService.reportCheck(first.planId(),
                new CheckReportRequest("idem-c", 2, T0.plus(Duration.ofMinutes(10)),
                        List.of("a", "b"), false));
        assertFalse(reported.late());
        ApiException separatorConflict = assertThrows(ApiException.class, () -> planService.reportCheck(
                first.planId(), new CheckReportRequest("idem-c", 2, T0.plus(Duration.ofMinutes(10)),
                        List.of("a|b"), false)));
        assertEquals("IDEMPOTENCY_CONFLICT", separatorConflict.getCode(),
                "合法文本含分隔符也须区分集合边界");
    }

    @Test
    void concurrentStartAndEmergencyReportYieldUniqueOccupancy() throws Exception {
        PlanResponse plan = publishDefault("p-cc-1", "RW-P11");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Object> startOutcome = new AtomicReference<>();
        AtomicReference<Object> reportOutcome = new AtomicReference<>();

        Future<?> startFuture = pool.submit(() -> {
            ready.countDown();
            start.await();
            try {
                startOutcome.set(planService.start(plan.planId(),
                        new PlanStartRequest("s-cc-1", 1, 1, T0.plus(Duration.ofMinutes(5)))));
            } catch (ApiException e) {
                startOutcome.set(e);
            }
            return null;
        });
        Future<?> reportFuture = pool.submit(() -> {
            ready.countDown();
            start.await();
            try {
                reportOutcome.set(runwayService.report(new ReportRequest("ev-cc-1", "RW-P11",
                        EventType.FOD, T0.plus(Duration.ofMinutes(6)))));
            } catch (ApiException e) {
                reportOutcome.set(e);
            }
            return null;
        });
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        startFuture.get(15, TimeUnit.SECONDS);
        reportFuture.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(reportOutcome.get() instanceof ReportResponse, "紧急接报须成功");
        PlanResponse finalPlan = planService.getPlan(plan.planId());
        boolean emergencyActive = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT COUNT(*) = 1 FROM runway_event WHERE runway_id = 'RW-P11' AND active_flag = 1",
                Boolean.class));
        assertTrue(emergencyActive);
        if (startOutcome.get() instanceof PlanResponse) {
            assertEquals("ABORTED", finalPlan.status(), "先开始后接报：计划须中止");
            assertNotNull(finalPlan.startedAt());
        } else {
            ApiException rejection = (ApiException) startOutcome.get();
            assertTrue("RUNWAY_EMERGENCY_ACTIVE".equals(rejection.getCode())
                    || "PLAN_NOT_STARTABLE".equals(rejection.getCode()));
            assertEquals("CANCELLED", finalPlan.status(), "先接报后开始：计划须取消且开始被拒绝");
        }
        Integer activePlans = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inspection_plan WHERE runway_id = 'RW-P11' AND active_flag = 1",
                Integer.class);
        assertEquals(0, activePlans, "紧急占用期间不得存在非终态例行计划");
    }

    @Test
    void concurrentPublishAndEmergencyReportYieldUniqueOccupancy() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Object> publishOutcome = new AtomicReference<>();
        AtomicReference<Object> reportOutcome = new AtomicReference<>();

        Future<?> publishFuture = pool.submit(() -> {
            ready.countDown();
            start.await();
            try {
                publishOutcome.set(publishDefault("p-cc-2", "RW-P12"));
            } catch (ApiException e) {
                publishOutcome.set(e);
            }
            return null;
        });
        Future<?> reportFuture = pool.submit(() -> {
            ready.countDown();
            start.await();
            try {
                reportOutcome.set(runwayService.report(new ReportRequest("ev-cc-2", "RW-P12",
                        EventType.FOD, T0)));
            } catch (ApiException e) {
                reportOutcome.set(e);
            }
            return null;
        });
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        publishFuture.get(15, TimeUnit.SECONDS);
        reportFuture.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(reportOutcome.get() instanceof ReportResponse, "紧急接报须成功");
        Integer activePlans = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inspection_plan WHERE runway_id = 'RW-P12' AND active_flag = 1",
                Integer.class);
        assertEquals(0, activePlans, "发布与接报并发后不得存在非终态例行计划");
        if (publishOutcome.get() instanceof PlanResponse created) {
            assertEquals("CANCELLED", planService.getPlan(created.planId()).status(),
                    "先发布后接报：计划须被同事务取消");
        } else {
            ApiException rejection = (ApiException) publishOutcome.get();
            assertEquals("RUNWAY_EMERGENCY_ACTIVE", rejection.getCode(), "先接报后发布：发布须被拒绝");
        }
    }

    @Test
    void crossRunwayPlansDoNotBlockEachOther() {
        PlanResponse planA = publishDefault("p-x-1", "RW-X1");
        PlanResponse planB = publishDefault("p-x-2", "RW-X2");

        // RW-X1 紧急接报只抢占本跑道计划
        runwayService.report(new ReportRequest("ev-x-1", "RW-X1", EventType.FOD, T0));
        assertEquals("CANCELLED", planService.getPlan(planA.planId()).status());
        assertEquals("APPROVED", planService.getPlan(planB.planId()).status(), "跨跑道计划互不影响");

        // RW-X2 日历独立
        CalendarImpactResponse impact = planService.calendarImpact("RW-X2");
        assertEquals(1, impact.currentVersion());
        assertTrue(impact.affectedPlans().isEmpty());
    }
}
