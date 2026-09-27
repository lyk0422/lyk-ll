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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 例行跑道巡检计划 / 航班日历 / 紧急抢占 的 H2 集成测试：
 * 覆盖主流程、失败分支（400/404/409/422）、并发唯一占用与幂等/结构化指纹边界。
 */
@SpringBootTest
class RoutineServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private RoutineService routineService;
    @Autowired
    private RunwayService runwayService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM routine_completion_report");
        jdbc.update("DELETE FROM routine_check_item");
        jdbc.update("DELETE FROM routine_plan");
        jdbc.update("DELETE FROM flight_occupancy");
        jdbc.update("DELETE FROM flight_calendar");
        jdbc.update("DELETE FROM runway_lock");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM notam_record");
        jdbc.update("DELETE FROM assessment_record");
        jdbc.update("DELETE FROM inspection_record");
        jdbc.update("DELETE FROM runway_event");
        jdbc.update("DELETE FROM weather_reading");
        // 重建种子日历版本1（空占用窗）
        jdbc.update("MERGE INTO flight_calendar (version, published_at, request_id, created_at) KEY (version)"
                + " VALUES (1, ?, NULL, ?)", java.sql.Timestamp.from(T0), java.sql.Timestamp.from(T0));
    }

    private PlanResponse publishPlan(String reqId, String runway, Instant start, Instant end, int duration,
                                     int calendarVersion) {
        return routineService.publishPlan(new PlanPublishRequest(reqId, runway, start, end, duration,
                calendarVersion));
    }

    /** 主流程：发布计划→开始→检查回报→正常完成（仅关闭计划），版本从1逐次+1。 */
    @Test
    void mainFlowPublishStartChecksComplete() {
        PlanResponse plan = publishPlan("p-1", "RWA", T0, T0.plus(Duration.ofHours(2)), 60, 1);
        assertEquals(RoutinePlanStatus.APPROVED.name(), plan.status());
        assertEquals(1, plan.version());
        assertTrue(plan.evidence().contains("\"conflict\":false"));

        PlanResponse started = routineService.startPlan(plan.planId(),
                new PlanStartRequest("s-1", 1, T0.plus(Duration.ofMinutes(10))));
        assertEquals(RoutinePlanStatus.STARTED.name(), started.status());
        assertEquals(2, started.version());
        assertEquals(1, started.calendarVersion());

        PlanCheckReportResponse checks = routineService.reportChecks(plan.planId(),
                new PlanCheckReportRequest("c-1", 2, T0.plus(Duration.ofMinutes(40)),
                        List.of(new CheckItemInput("LIGHT", "跑道边灯|应急灯", true,
                                        T0.plus(Duration.ofMinutes(30))),
                                new CheckItemInput("SIGN", "标志清晰", true,
                                        T0.plus(Duration.ofMinutes(35))))));
        assertFalse(checks.late());
        assertEquals(2, checks.savedItems().size());
        assertEquals(3, checks.version());

        PlanCompletionResponse done = routineService.completePlan(plan.planId(),
                new PlanCompletionRequest("done-1", 3, T0.plus(Duration.ofMinutes(70))));
        assertFalse(done.late());
        assertEquals(RoutinePlanStatus.COMPLETED.name(), done.status());
        assertEquals(4, done.version());

        // 终态释放获准位：同跑道同窗可另建新计划
        PlanResponse another = publishPlan("p-1b", "RWA", T0, T0.plus(Duration.ofHours(2)), 60, 1);
        assertEquals(RoutinePlanStatus.APPROVED.name(), another.status());
    }

    /** 巡检时长不完整落在窗口：422。 */
    @Test
    void inspectionLongerThanWindowRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> publishPlan("p-long", "RWA", T0,
                T0.plus(Duration.ofMinutes(30)), 60, 1));
        assertEquals(422, ex.getStatus().value());
        assertEquals("INSPECTION_NOT_WITHIN_WINDOW", ex.getCode());
        assertEquals(0, planCount());
    }

    /** 非法窗口（结束<=开始）：400。 */
    @Test
    void invalidWindowIsBadRequest() {
        ApiException ex = assertThrows(ApiException.class, () -> publishPlan("p-bad", "RWA",
                T0.plus(Duration.ofHours(1)), T0, 30, 1));
        assertEquals(400, ex.getStatus().value());
    }

    /** 与航班占用窗正长度相交拒绝（422附证据）；端点相接合法。 */
    @Test
    void occupancyOverlapRejectedButEndpointTouchLegal() {
        // 发布日历版本2：占用窗 [01:00,02:00)
        routineService.publishCalendar(new CalendarPublishRequest("cal-2", 1, T0,
                List.of(new FlightWindowInput("RWB", T0.plus(Duration.ofHours(1)),
                        T0.plus(Duration.ofHours(2))))));

        // 巡检区间 [00:30,01:30) 与占用正长度相交 → 拒绝
        ApiException overlap = assertThrows(ApiException.class, () -> publishPlan("p-overlap", "RWB",
                T0.plus(Duration.ofMinutes(30)), T0.plus(Duration.ofHours(3)), 60, 2));
        assertEquals("OCCUPANCY_CONFLICT", overlap.getCode());
        assertNotNull(overlap.getEvidence());
        assertTrue(overlap.getEvidence().contains("\"conflict\":true"));

        // 端点相接：巡检 [00:00,01:00) 结束恰为占用开始 → 合法
        PlanResponse touch = publishPlan("p-touch", "RWB", T0, T0.plus(Duration.ofHours(3)), 60, 2);
        assertEquals(RoutinePlanStatus.APPROVED.name(), touch.status());
    }

    /** 同跑道同窗只能一个获准计划；跨跑道/不同窗互不阻塞。 */
    @Test
    void sameRunwaySameWindowOnlyOneApproved() {
        publishPlan("slot-1", "RWC", T0, T0.plus(Duration.ofHours(2)), 60, 1);
        ApiException ex = assertThrows(ApiException.class,
                () -> publishPlan("slot-2", "RWC", T0, T0.plus(Duration.ofHours(2)), 60, 1));
        assertEquals("PLAN_SLOT_OCCUPIED", ex.getCode());

        // 不同窗口允许
        assertDoesNotThrow(() -> publishPlan("slot-3", "RWC", T0.plus(Duration.ofHours(3)),
                T0.plus(Duration.ofHours(5)), 60, 1));
        // 不同跑道同窗允许
        assertDoesNotThrow(() -> publishPlan("slot-4", "RWD", T0, T0.plus(Duration.ofHours(2)), 60, 1));
    }

    /** 日历变更：未开始计划待重审（版本+1），已开始计划不自动平移。 */
    @Test
    void calendarChangeMarksApprovedForReviewButDoesNotShiftStarted() {
        PlanResponse notStarted = publishPlan("rv-1", "RWE", T0, T0.plus(Duration.ofHours(2)), 60, 1);
        PlanResponse started = publishPlan("rv-2", "RWF", T0, T0.plus(Duration.ofHours(2)), 60, 1);
        routineService.startPlan(started.planId(), new PlanStartRequest("rv-2s", 1, T0));

        CalendarPublishResponse published = routineService.publishCalendar(new CalendarPublishRequest("cal-x",
                1, T0.plus(Duration.ofMinutes(5)), List.of()));
        assertEquals(2, published.version());
        assertTrue(published.plansNeedingReview().contains(notStarted.planId()));
        assertFalse(published.plansNeedingReview().contains(started.planId()));

        assertEquals(RoutinePlanStatus.NEEDS_REVIEW.name(),
                routineService.getPlan(notStarted.planId()).status());
        // 已开始计划仍 STARTED，开始时刻不被平移
        PlanResponse stillStarted = routineService.getPlan(started.planId());
        assertEquals(RoutinePlanStatus.STARTED.name(), stillStarted.status());
        assertEquals(T0, stillStarted.startedAt());
    }

    /** 待重审计划：最新版本仍冲突则开始422；冲突消除后重审通过可开始并重新冻结版本。 */
    @Test
    void needsReviewBlocksStartWhileConflictThenReconfirms() {
        PlanResponse plan = publishPlan("nr-1", "RWG", T0, T0.plus(Duration.ofHours(3)), 60, 1);
        // v2 增加与巡检 [00:00,01:00) 冲突的占用
        routineService.publishCalendar(new CalendarPublishRequest("cal-nr2", 1, T0,
                List.of(new FlightWindowInput("RWG", T0.plus(Duration.ofMinutes(30)),
                        T0.plus(Duration.ofMinutes(90))))));
        assertEquals(RoutinePlanStatus.NEEDS_REVIEW.name(), routineService.getPlan(plan.planId()).status());
        ApiException blocked = assertThrows(ApiException.class, () -> routineService.startPlan(plan.planId(),
                new PlanStartRequest("nr-s1", 2, T0)));
        assertEquals("CALENDAR_REVIEW_CONFLICT", blocked.getCode());

        // v3 移除占用，重审通过，开始并冻结到版本3
        routineService.publishCalendar(new CalendarPublishRequest("cal-nr3", 2,
                T0.plus(Duration.ofMinutes(10)), List.of()));
        PlanResponse started = routineService.startPlan(plan.planId(),
                new PlanStartRequest("nr-s2", 2, T0));
        assertEquals(RoutinePlanStatus.STARTED.name(), started.status());
        assertEquals(3, started.calendarVersion());
    }

    /** 开始时存在进行中紧急关闭：422（直接插入进行中紧急事件，规避接报对计划的抢占）。 */
    @Test
    void startBlockedByActiveEmergencyClosure() {
        PlanResponse plan = publishPlan("em-1", "RWH", T0, T0.plus(Duration.ofHours(2)), 60, 1);
        jdbc.update("INSERT INTO runway_event (runway_id, event_type, status, reported_at, closed_at,"
                        + " inspection_deadline, escalated, assessment_type, active_flag, request_id,"
                        + " created_at, updated_at) VALUES ('RWH','FOD','CLOSED',?,?,?,FALSE,NULL,1,'em-direct',?,?)",
                java.sql.Timestamp.from(T0), java.sql.Timestamp.from(T0),
                java.sql.Timestamp.from(T0.plus(Duration.ofMinutes(15))),
                java.sql.Timestamp.from(T0), java.sql.Timestamp.from(T0));
        ApiException ex = assertThrows(ApiException.class, () -> routineService.startPlan(plan.planId(),
                new PlanStartRequest("em-s", 1, T0.plus(Duration.ofMinutes(5)))));
        assertEquals("EMERGENCY_CLOSURE_ACTIVE", ex.getCode());
    }

    /** 过期 expectedVersion：409。 */
    @Test
    void staleExpectedVersionConflicts() {
        PlanResponse plan = publishPlan("ver-1", "RWI", T0, T0.plus(Duration.ofHours(2)), 60, 1);
        routineService.publishCalendar(new CalendarPublishRequest("cal-ver", 1, T0, List.of()));
        ApiException ex = assertThrows(ApiException.class, () -> routineService.startPlan(plan.planId(),
                new PlanStartRequest("ver-s", 1, T0)));
        assertEquals(409, ex.getStatus().value());
        assertEquals("PLAN_VERSION_CONFLICT", ex.getCode());
    }

    /** 对象不存在：404。 */
    @Test
    void missingPlanReturns404() {
        ApiException ex = assertThrows(ApiException.class, () -> routineService.getPlan(99999L));
        assertEquals(404, ex.getStatus().value());
    }

    /**
     * 紧急抢占：未开始计划取消；已开始计划中止且保留已完成检查项；
     * 之后迟到检查/完成回报仅登记，不开放跑道；紧急事件结束后可另建新计划，不复活被抢占项。
     */
    @Test
    void emergencyPreemptsAndLateReportsDoNotReopen() {
        // 未开始计划
        PlanResponse notStarted = publishPlan("pre-1", "RWJ", T0, T0.plus(Duration.ofHours(4)), 60, 1);
        // 已开始计划，并回报2个检查项
        PlanResponse started = publishPlan("pre-2", "RWJ", T0.plus(Duration.ofHours(5)),
                T0.plus(Duration.ofHours(9)), 60, 1);
        routineService.startPlan(started.planId(), new PlanStartRequest("pre-2s", 1,
                T0.plus(Duration.ofHours(5))));
        routineService.reportChecks(started.planId(), new PlanCheckReportRequest("pre-2c", 2,
                T0.plus(Duration.ofHours(6)),
                List.of(new CheckItemInput("A", "项A", true, T0.plus(Duration.ofHours(5).plusMinutes(30))),
                        new CheckItemInput("B", "项B", false, T0.plus(Duration.ofHours(5).plusMinutes(40))))));

        // 接报紧急关闭：同事务抢占
        ReportResponse emergency = runwayService.report(
                new ReportRequest("em-pre", "RWJ", EventType.PAVEMENT_DAMAGE, T0.plus(Duration.ofHours(7))));

        assertEquals(RoutinePlanStatus.CANCELLED.name(), routineService.getPlan(notStarted.planId()).status());
        PlanResponse aborted = routineService.getPlan(started.planId());
        assertEquals(RoutinePlanStatus.ABORTED.name(), aborted.status());
        assertEquals(emergency.eventId(), aborted.emergencyEventId());

        // 已完成检查项保留（非迟到项仍为2条）
        Integer kept = jdbc.queryForObject(
                "SELECT COUNT(*) FROM routine_check_item WHERE plan_id = ? AND late = 0",
                Integer.class, started.planId());
        assertEquals(2, kept, "紧急中止须保存已完成检查项");

        // 迟到检查回报仅登记 late=1，计划状态不变、版本不变
        int versionBeforeLate = aborted.version();
        PlanCheckReportResponse lateChecks = routineService.reportChecks(started.planId(),
                new PlanCheckReportRequest("late-c", versionBeforeLate, T0.plus(Duration.ofHours(8)),
                        List.of(new CheckItemInput("LATE", "迟到项", true, T0.plus(Duration.ofHours(8))))));
        assertTrue(lateChecks.late());
        assertEquals(RoutinePlanStatus.ABORTED.name(), lateChecks.status());
        assertEquals(versionBeforeLate, lateChecks.version());
        Integer lateItems = jdbc.queryForObject(
                "SELECT COUNT(*) FROM routine_check_item WHERE plan_id = ? AND late = 1",
                Integer.class, started.planId());
        assertEquals(1, lateItems);

        // 迟到完成回报只登记，不开放跑道
        PlanCompletionResponse lateDone = routineService.completePlan(started.planId(),
                new PlanCompletionRequest("late-done", versionBeforeLate, T0.plus(Duration.ofHours(9))));
        assertTrue(lateDone.late());
        assertEquals(RoutinePlanStatus.ABORTED.name(), lateDone.status());
        Integer lateCompletions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM routine_completion_report WHERE plan_id = ? AND late = 1",
                Integer.class, started.planId());
        assertEquals(1, lateCompletions);
        // 跑道仍处紧急关闭（未被例行完成开放）
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM runway_event WHERE runway_id = 'RWJ' AND active_flag = 1",
                Integer.class) == 1);

        // 紧急事件评估、恢复开放走既有流程（例行完成不取代它）
        runwayService.startInspection(emergency.eventId(),
                new InspectionRequest(T0.plus(Duration.ofHours(7).plusMinutes(5)), 2));
        runwayService.assess(emergency.eventId(), new AssessmentRequest("pre-a",
                new java.math.BigDecimal("0.60"), AssessmentType.CLEAR_TO_REOPEN,
                T0.plus(Duration.ofHours(7).plusMinutes(10))));
        runwayService.reopen(emergency.eventId(),
                new ReopenRequest("pre-r", T0.plus(Duration.ofHours(7).plusMinutes(15))));

        // 紧急结束后可另建新计划；被中止计划不复活
        PlanResponse fresh = publishPlan("pre-3", "RWJ", T0.plus(Duration.ofHours(10)),
                T0.plus(Duration.ofHours(12)), 60, 1);
        assertEquals(RoutinePlanStatus.APPROVED.name(), fresh.status());
        assertEquals(RoutinePlanStatus.ABORTED.name(), routineService.getPlan(started.planId()).status());
    }

    /** 未开始计划不受理检查回报：422。 */
    @Test
    void checksBeforeStartRejected() {
        PlanResponse plan = publishPlan("bs-1", "RWK", T0, T0.plus(Duration.ofHours(2)), 60, 1);
        ApiException ex = assertThrows(ApiException.class, () -> routineService.reportChecks(plan.planId(),
                new PlanCheckReportRequest("bs-c", 1, T0,
                        List.of(new CheckItemInput("X", "x", true, T0)))));
        assertEquals("PLAN_NOT_STARTED", ex.getCode());
    }

    /** 空检查项集合：400。 */
    @Test
    void emptyCheckItemsBadRequest() {
        PlanResponse plan = publishPlan("ec-1", "RWL", T0, T0.plus(Duration.ofHours(2)), 60, 1);
        routineService.startPlan(plan.planId(), new PlanStartRequest("ec-s", 1, T0));
        ApiException ex = assertThrows(ApiException.class, () -> routineService.reportChecks(plan.planId(),
                new PlanCheckReportRequest("ec-c", 2, T0, List.of())));
        assertEquals(400, ex.getStatus().value());
        assertEquals("EMPTY_CHECK_ITEMS", ex.getCode());
    }

    /** 幂等：同键同参重放首次结果；异参409；失败不占键。 */
    @Test
    void idempotentReplayConflictAndFailureDoesNotOccupyKey() {
        PlanResponse first = publishPlan("idem-p", "RWM", T0, T0.plus(Duration.ofHours(2)), 60, 1);
        PlanResponse replay = publishPlan("idem-p", "RWM", T0, T0.plus(Duration.ofHours(2)), 60, 1);
        assertEquals(first.planId(), replay.planId());
        assertEquals(1, planCount());

        // 同键异参 → 409
        ApiException conflict = assertThrows(ApiException.class, () -> publishPlan("idem-p", "RWM",
                T0, T0.plus(Duration.ofHours(2)), 45, 1));
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());

        // 失败请求不占键：先用占用冲突制造失败，再以同 requestId 合法请求应成功
        routineService.publishCalendar(new CalendarPublishRequest("idem-cal", 1, T0,
                List.of(new FlightWindowInput("RWM2", T0, T0.plus(Duration.ofHours(1))))));
        assertThrows(ApiException.class, () -> routineService.publishPlan(new PlanPublishRequest("idem-fail",
                "RWM2", T0, T0.plus(Duration.ofHours(2)), 60, 2)));
        assertDoesNotThrow(() -> routineService.publishPlan(new PlanPublishRequest("idem-fail",
                "RWM2", T0.plus(Duration.ofHours(2)), T0.plus(Duration.ofHours(4)), 60, 2)));
        Integer idemFailRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'idem-fail'", Integer.class);
        assertEquals(1, idemFailRows, "失败不占键，仅成功后有一条幂等记录");
    }

    /**
     * 结构化指纹无歧义：朴素 "|" 拼接会碰撞的两组集合，结构化编码指纹不同，同键异参判409。
     * 集合A：单项 [code="x", text="a|b"]；集合B：两项 [code="x",text="a"]、[code="b",text="z"]。
     */
    @Test
    void structuredFingerprintDistinguishesDelimiterText() {
        PlanResponse plan = publishPlan("fp-1", "RWN", T0, T0.plus(Duration.ofHours(2)), 60, 1);
        routineService.startPlan(plan.planId(), new PlanStartRequest("fp-s", 1, T0));

        List<CheckItemInput> setA = List.of(new CheckItemInput("x", "a|b", true, T0));
        List<CheckItemInput> setB = List.of(new CheckItemInput("x", "a", true, T0),
                new CheckItemInput("b", "z", true, T0));
        routineService.reportChecks(plan.planId(),
                new PlanCheckReportRequest("fp-key", 2, T0, setA));
        ApiException ex = assertThrows(ApiException.class, () -> routineService.reportChecks(plan.planId(),
                new PlanCheckReportRequest("fp-key", 2, T0, setB)));
        assertEquals("IDEMPOTENCY_CONFLICT", ex.getCode());
    }

    /** 并发日历发布：相同 expectedVersion 仅一个成功产生新版本，其余409。 */
    @Test
    void concurrentCalendarPublishesOnlyOneSucceeds() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    routineService.publishCalendar(new CalendarPublishRequest("cc-cal-" + idx, 1,
                            T0.plusSeconds(idx), List.of()));
                    success.incrementAndGet();
                } catch (ApiException e) {
                    if ("CALENDAR_VERSION_CONFLICT".equals(e.getCode())) {
                        conflict.incrementAndGet();
                    } else {
                        throw e;
                    }
                }
                return null;
            }));
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        for (Future<?> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(1, success.get());
        assertEquals(threads - 1, conflict.get());
        assertEquals(2, jdbc.queryForObject("SELECT MAX(version) FROM flight_calendar", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM flight_calendar", Integer.class));
    }

    /**
     * 并发：例行开始 与 紧急接报 同跑道竞争唯一占用。
     * 任一先后，最终都满足：紧急事件进行中且计划为终态（取消或中止），不存在“巡检进行中且紧急关闭进行中”。
     */
    @Test
    void concurrentStartAndEmergencyYieldUniqueOccupancy() throws Exception {
        PlanResponse plan = publishPlan("cc-occ", "RWO", T0, T0.plus(Duration.ofHours(2)), 60, 1);
        int pairs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(pairs * 2);
        CountDownLatch ready = new CountDownLatch(pairs * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    routineService.startPlan(plan.planId(),
                            new PlanStartRequest("cc-start-" + idx, 1, T0.plusSeconds(idx)));
                } catch (ApiException expected) {
                    // 版本冲突 / 紧急关闭进行中 / 计划已不可开始 均为合法竞争结果
                }
                return null;
            }));
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    runwayService.report(new ReportRequest("cc-em-" + idx, "RWO", EventType.FOD,
                            T0.plusSeconds(idx)));
                } catch (ApiException expected) {
                    // 同跑道仅一个进行中紧急事件，其余 RUNWAY_ACTIVE_EVENT_EXISTS 合法
                }
                return null;
            }));
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();

        Integer activeEmergencies = jdbc.queryForObject(
                "SELECT COUNT(*) FROM runway_event WHERE runway_id = 'RWO' AND active_flag = 1", Integer.class);
        assertEquals(1, activeEmergencies, "同跑道唯一进行中紧急事件");
        String status = jdbc.queryForObject("SELECT status FROM routine_plan WHERE id = ?",
                String.class, plan.planId());
        assertTrue(status.equals("CANCELLED") || status.equals("ABORTED"),
                "竞争结束后计划必须为终态，实际=" + status);
    }

    /** 跨跑道：一条跑道紧急关闭不影响另一条跑道的例行计划。 */
    @Test
    void emergencyOnOneRunwayDoesNotBlockOther() {
        PlanResponse a = publishPlan("xa", "RWP", T0, T0.plus(Duration.ofHours(2)), 60, 1);
        PlanResponse b = publishPlan("xb", "RWQ", T0, T0.plus(Duration.ofHours(2)), 60, 1);
        runwayService.report(new ReportRequest("xem", "RWP", EventType.FOD, T0));
        assertEquals(RoutinePlanStatus.CANCELLED.name(), routineService.getPlan(a.planId()).status());
        assertEquals(RoutinePlanStatus.APPROVED.name(), routineService.getPlan(b.planId()).status());
        assertDoesNotThrow(() -> routineService.startPlan(b.planId(),
                new PlanStartRequest("xbs", 1, T0.plus(Duration.ofMinutes(5)))));
    }

    /** 日历影响查询：返回版本占用窗与待重审计划。 */
    @Test
    void calendarImpactQuery() {
        PlanResponse plan = publishPlan("imp-1", "RWR", T0, T0.plus(Duration.ofHours(3)), 60, 1);
        routineService.publishCalendar(new CalendarPublishRequest("imp-cal", 1, T0,
                List.of(new FlightWindowInput("RWR", T0.plus(Duration.ofMinutes(30)),
                        T0.plus(Duration.ofMinutes(90))))));
        CalendarImpactResponse impact = routineService.getCalendarImpact(2);
        assertEquals(1, impact.occupancies().size());
        assertTrue(impact.impactedPlans().stream().anyMatch(p -> p.planId() == plan.planId()));
    }

    private int planCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM routine_plan", Integer.class);
        return count == null ? 0 : count;
    }
}
