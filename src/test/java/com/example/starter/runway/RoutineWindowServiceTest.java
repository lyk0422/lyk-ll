package com.example.starter.runway;

import com.example.starter.runway.dto.CalendarPublishRequest;
import com.example.starter.runway.dto.CalendarPublishResponse;
import com.example.starter.runway.dto.EmergencyEndRequest;
import com.example.starter.runway.dto.EmergencyReportRequest;
import com.example.starter.runway.dto.EmergencyReportResponse;
import com.example.starter.runway.dto.EmergencyView;
import com.example.starter.runway.dto.FlightWindowInput;
import com.example.starter.runway.dto.PlanCheckReportRequest;
import com.example.starter.runway.dto.PlanCheckReportResponse;
import com.example.starter.runway.dto.PlanPublishRequest;
import com.example.starter.runway.dto.PlanPublishResponse;
import com.example.starter.runway.dto.PlanReviewRequest;
import com.example.starter.runway.dto.PlanStartRequest;
import com.example.starter.runway.dto.PlanStartResponse;
import com.example.starter.runway.dto.RoutinePlanView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 例行巡检窗口编排与紧急关闭抢占：H2(MODE=MySQL) 业务/事务/并发/幂等集成测试。
 */
@SpringBootTest
class RoutineWindowServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-27T02:00:00Z");

    @Autowired
    private RoutineWindowService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM routine_check_item");
        jdbc.update("DELETE FROM routine_plan");
        jdbc.update("DELETE FROM runway_emergency");
        jdbc.update("DELETE FROM flight_occupancy");
        jdbc.update("DELETE FROM flight_calendar_version");
        jdbc.update("DELETE FROM notam_record");
        jdbc.update("DELETE FROM assessment_record");
        jdbc.update("DELETE FROM inspection_record");
        jdbc.update("DELETE FROM runway_event");
        jdbc.update("DELETE FROM weather_reading");
    }

    private CalendarPublishResponse publishCalendar(String requestId, List<FlightWindowInput> flights) {
        return service.publishCalendar(new CalendarPublishRequest(requestId, T0, flights));
    }

    private PlanPublishRequest planRequest(String requestId, String runway, Instant start, Instant end,
                                           int durationMinutes, List<String> items) {
        return new PlanPublishRequest(requestId, runway, start, end, durationMinutes, items);
    }

    private FlightWindowInput flight(String flightNo, String runway, Instant start, Instant end) {
        return new FlightWindowInput(flightNo, runway, start, end);
    }

    @Test
    void calendarVersionsStartAtOneAndIncrementAndAreIdempotent() {
        CalendarPublishResponse v1 = publishCalendar("cal-1", List.of());
        assertEquals(1, v1.version());
        CalendarPublishResponse v1Replay = publishCalendar("cal-1", List.of());
        assertEquals(1, v1Replay.version(), "同键同参重放首次结果");

        CalendarPublishResponse v2 = publishCalendar("cal-2",
                List.of(flight("CA101", "RW01", T0, T0.plus(Duration.ofMinutes(30)))));
        assertEquals(2, v2.version());
        assertEquals(1, v2.flightCount());

        ApiException conflict = assertThrows(ApiException.class, () -> publishCalendar("cal-1",
                List.of(flight("CA999", "RW01", T0, T0.plus(Duration.ofMinutes(5))))));
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());
        Integer versions = jdbc.queryForObject("SELECT COUNT(*) FROM flight_calendar_version", Integer.class);
        assertEquals(2, versions, "异参409不得新增版本");
    }

    @Test
    void planPublishesFreezesEvidenceAndRejectsPositiveOverlapButAllowsEndpointTouch() {
        publishCalendar("cal-a", List.of(
                flight("CA1", "RW01", T0.plus(Duration.ofMinutes(20)), T0.plus(Duration.ofMinutes(30))),
                flight("CA2", "RW01", T0.plus(Duration.ofMinutes(60)), T0.plus(Duration.ofMinutes(70)))));

        // 巡检窗 [00:00,00:30) 时长30分钟：与 CA1 在 20~30 正长度相交
        PlanConflictException conflict = assertThrows(PlanConflictException.class, () -> service.publishPlan(
                planRequest("plan-bad", "RW01", T0, T0.plus(Duration.ofMinutes(30)), 30,
                        List.of("LIGHTS"))));
        assertEquals(422, conflict.getStatus().value());
        assertEquals(1, conflict.getConflicts().size());
        assertEquals("CA1", conflict.getConflicts().get(0).flightNo());
        assertEquals(10, conflict.getConflicts().get(0).overlapMinutes());
        Integer planCount = jdbc.queryForObject("SELECT COUNT(*) FROM routine_plan", Integer.class);
        assertEquals(0, planCount, "业务拒绝不得残留计划");

        // 端点相接合法：巡检 [00:30,00:60) 与 CA1 仅在 30 分相接、与 CA2 在 60 分相接
        PlanPublishResponse approved = service.publishPlan(planRequest("plan-ok", "RW01",
                T0.plus(Duration.ofMinutes(30)), T0.plus(Duration.ofMinutes(60)), 30,
                List.of("LIGHTS", "SURFACE")));
        assertEquals(RoutinePlanStatus.APPROVED.name(), approved.status());
        assertEquals(1, approved.version());
        assertEquals(1, approved.frozenCalendarVersion());
        assertEquals(T0.plus(Duration.ofMinutes(60)), approved.inspectionEnd());

        RoutinePlanView view = service.getPlan(approved.id());
        assertEquals(2, view.checkedFlights().size(), "冻结证据保存该跑道该版本全部受检航班窗");
        assertTrue(view.conflicts().isEmpty());
        assertEquals(List.of("LIGHTS", "SURFACE"),
                view.checkItems().stream().map(c -> c.itemCode()).toList());
    }

    @Test
    void invalidWindowAndDurationReturn400AndFailuresDoNotOccupyIdempotencyKey() {
        publishCalendar("cal-b", List.of());
        ApiException bad = assertThrows(ApiException.class, () -> service.publishPlan(planRequest(
                "plan-retry", "RW01", T0.plus(Duration.ofMinutes(10)), T0, 5, List.of("X"))));
        assertEquals(400, bad.getStatus().value());

        // 失败不占键：同键修正参数后成功
        PlanPublishResponse ok = service.publishPlan(planRequest("plan-retry", "RW01",
                T0, T0.plus(Duration.ofMinutes(20)), 10, List.of("X")));
        assertEquals(RoutinePlanStatus.APPROVED.name(), ok.status());

        ApiException noCalendar = assertThrows(ApiException.class, () -> {
            jdbc.update("DELETE FROM flight_occupancy");
            jdbc.update("DELETE FROM flight_calendar_version");
            service.publishPlan(planRequest("plan-nocal", "RW09", T0, T0.plus(Duration.ofMinutes(20)),
                    10, List.of("X")));
        });
        assertEquals(400, noCalendar.getStatus().value());
    }

    @Test
    void sameRunwaySameWindowOnlyOneLivePlan() {
        publishCalendar("cal-c", List.of());
        service.publishPlan(planRequest("dup-1", "RW02", T0, T0.plus(Duration.ofMinutes(30)), 20,
                List.of("A")));
        ApiException dup = assertThrows(ApiException.class, () -> service.publishPlan(
                planRequest("dup-2", "RW02", T0, T0.plus(Duration.ofMinutes(30)), 20, List.of("B"))));
        assertEquals("DUPLICATE_PLAN_WINDOW", dup.getCode());
        assertEquals(409, dup.getStatus().value());

        // 不同窗口可各自获准
        assertDoesNotThrow(() -> service.publishPlan(planRequest("dup-3", "RW02",
                T0.plus(Duration.ofHours(2)), T0.plus(Duration.ofHours(3)), 20, List.of("B"))));
    }

    @Test
    void calendarChangeMarksOnlyNotStartedPlansForReviewAndReviewReFreezes() {
        publishCalendar("cal-d1", List.of());
        PlanPublishResponse p1 = service.publishPlan(planRequest("rp-1", "RW03",
                T0.plus(Duration.ofHours(5)), T0.plus(Duration.ofHours(6)), 30, List.of("A")));
        PlanPublishResponse p2 = service.publishPlan(planRequest("rp-2", "RW03",
                T0.plus(Duration.ofHours(8)), T0.plus(Duration.ofHours(9)), 30, List.of("B")));
        service.startPlan(p2.id(), new PlanStartRequest("start-p2", 1, 1, T0.plus(Duration.ofHours(8))));

        // 升版 v2：p1 待重审，已开始的 p2 不自动平移
        CalendarPublishResponse after = publishCalendar("cal-d2", List.of(
                flight("CA9", "RW03",
                        T0.plus(Duration.ofHours(5)).plus(Duration.ofMinutes(10)),
                        T0.plus(Duration.ofHours(5)).plus(Duration.ofMinutes(20)))));
        assertEquals(List.of(p1.id()), after.needsReviewPlanIds());
        assertEquals(RoutinePlanStatus.NEEDS_REVIEW.name(), service.getPlan(p1.id()).status());
        assertEquals(RoutinePlanStatus.IN_PROGRESS.name(), service.getPlan(p2.id()).status());

        // 待重审直接开始 → 422
        ApiException notStartable = assertThrows(ApiException.class, () -> service.startPlan(p1.id(),
                new PlanStartRequest("start-p1-bad", 2, 2, T0.plus(Duration.ofHours(5)))));
        assertEquals("PLAN_NEEDS_REVIEW", notStartable.getCode());

        // 按冲突的 v2 重审 → 422
        PlanConflictException stillConflict = assertThrows(PlanConflictException.class,
                () -> service.reviewPlan(p1.id(), new PlanReviewRequest("rev-v2", 2, 2)));
        assertEquals(1, stillConflict.getConflicts().size());

        // v3 清空该跑道冲突后重审通过，冻结版本变为3，版本号加1
        publishCalendar("cal-d3", List.of());
        PlanPublishResponse reviewed = service.reviewPlan(p1.id(), new PlanReviewRequest("rev-v3", 2, 3));
        assertEquals(RoutinePlanStatus.APPROVED.name(), reviewed.status());
        assertEquals(3, reviewed.version());
        assertEquals(3, reviewed.frozenCalendarVersion());
        RoutinePlanView view = service.getPlan(p1.id());
        assertEquals(3, view.frozenCalendarVersion());
        assertEquals(3, view.latestCalendarVersion());
    }

    @Test
    void startRechecksCalendarAndEmergencyAndVersion() {
        publishCalendar("cal-e", List.of());
        PlanPublishResponse plan = service.publishPlan(planRequest("sp-1", "RW04",
                T0.plus(Duration.ofHours(1)), T0.plus(Duration.ofHours(2)), 30, List.of("A")));

        // 期望版本错误 → 409
        ApiException versionBad = assertThrows(ApiException.class, () -> service.startPlan(plan.id(),
                new PlanStartRequest("start-vbad", 99, 1, T0.plus(Duration.ofHours(1)))));
        assertEquals("VERSION_CONFLICT", versionBad.getCode());

        // 日历升版后，持旧版本号开始 → 409（版本已被升版动作变更）
        publishCalendar("cal-e2", List.of());
        ApiException staleVersion = assertThrows(ApiException.class, () -> service.startPlan(plan.id(),
                new PlanStartRequest("start-stale", 1, 1, T0.plus(Duration.ofHours(1)))));
        assertEquals("VERSION_CONFLICT", staleVersion.getCode());
        // 刷新版本号但仍按旧日历版本开始 → 422，计划仍须重审
        ApiException staleCalendar = assertThrows(ApiException.class, () -> service.startPlan(plan.id(),
                new PlanStartRequest("start-stale-cal", 2, 1, T0.plus(Duration.ofHours(1)))));
        assertEquals("PLAN_NEEDS_REVIEW", staleCalendar.getCode());

        // 计划已转待重审，重审后按最新版本开始成功
        service.reviewPlan(plan.id(), new PlanReviewRequest("sp-rev", 2, 2));
        PlanStartResponse started = service.startPlan(plan.id(),
                new PlanStartRequest("start-ok", 3, 2, T0.plus(Duration.ofHours(1).plusMinutes(10))));
        assertEquals(RoutinePlanStatus.IN_PROGRESS.name(), started.status());
        assertEquals(4, started.version());

        // 实际巡检超出窗口 → 400（另一个计划）
        PlanPublishResponse p2 = service.publishPlan(planRequest("sp-2", "RW04",
                T0.plus(Duration.ofHours(3)), T0.plus(Duration.ofHours(4)), 30, List.of("A")));
        ApiException outside = assertThrows(ApiException.class, () -> service.startPlan(p2.id(),
                new PlanStartRequest("start-out", 1, 2,
                        T0.plus(Duration.ofHours(3).plusMinutes(45)))));
        assertEquals("INSPECTION_OUTSIDE_WINDOW", outside.getCode());
        assertEquals(400, outside.getStatus().value());

        // 跑道接报紧急关闭：未开始的 p3 同事务被取消，开始不再受理（并发放置分支见并发测试）
        PlanPublishResponse p3 = service.publishPlan(planRequest("sp-3", "RW04",
                T0.plus(Duration.ofHours(5)), T0.plus(Duration.ofHours(6)), 30, List.of("A")));
        service.reportEmergency(new EmergencyReportRequest("sp-em", "RW04", "险情",
                T0.plus(Duration.ofHours(4).plusMinutes(30))));
        assertEquals(RoutinePlanStatus.CANCELLED.name(), service.getPlan(p3.id()).status());
        ApiException underEmergency = assertThrows(ApiException.class, () -> service.startPlan(p3.id(),
                new PlanStartRequest("start-em", 2, 2, T0.plus(Duration.ofHours(5)))));
        assertEquals("PLAN_NOT_STARTABLE", underEmergency.getCode());
    }

    @Test
    void emergencyPreemptsCancelsAndAbortsAndLateChecksNeverReopen() {
        publishCalendar("cal-f", List.of());
        PlanPublishResponse notStarted = service.publishPlan(planRequest("em-ns", "RW05",
                T0.plus(Duration.ofHours(1)), T0.plus(Duration.ofHours(2)), 30, List.of("N1", "N2")));
        PlanPublishResponse inProgress = service.publishPlan(planRequest("em-ip", "RW05",
                T0.plus(Duration.ofHours(3)), T0.plus(Duration.ofHours(4)), 30, List.of("I1", "I2")));
        service.startPlan(inProgress.id(),
                new PlanStartRequest("em-ip-start", 1, 1, T0.plus(Duration.ofHours(3))));
        PlanCheckReportResponse firstCheck = service.reportCheck(inProgress.id(),
                new PlanCheckReportRequest("em-ip-c1", 2, "I1",
                        T0.plus(Duration.ofHours(3).plusMinutes(10))));
        assertFalse(firstCheck.planCompleted());
        assertEquals(3, firstCheck.planVersion(), "成功检查回报使版本加1");

        EmergencyReportResponse emergency = service.reportEmergency(new EmergencyReportRequest(
                "em-1", "RW05", "疑似跑道侵入", T0.plus(Duration.ofHours(3).plusMinutes(20))));
        assertEquals(List.of(notStarted.id()), emergency.cancelledPlanIds());
        assertEquals(List.of(inProgress.id()), emergency.abortedPlanIds());

        assertEquals(RoutinePlanStatus.CANCELLED.name(), service.getPlan(notStarted.id()).status());
        RoutinePlanView abortedView = service.getPlan(inProgress.id());
        assertEquals(RoutinePlanStatus.ABORTED.name(), abortedView.status());
        assertEquals(T0.plus(Duration.ofHours(3).plusMinutes(20)), abortedView.preemptedAt());
        assertEquals(1, abortedView.checkItems().stream().filter(c -> c.completedAt() != null).count(),
                "已完成检查项须保留");

        // 同跑道第二个进行中紧急事件 → 409
        ApiException again = assertThrows(ApiException.class, () -> service.reportEmergency(
                new EmergencyReportRequest("em-2", "RW05", "二次险情",
                        T0.plus(Duration.ofHours(3).plusMinutes(25)))));
        assertEquals("EMERGENCY_ALREADY_ACTIVE", again.getCode());

        // 中止后的新检查回报：只登记迟到，计划维持 ABORTED，不开放跑道
        PlanCheckReportResponse late = service.reportCheck(inProgress.id(),
                new PlanCheckReportRequest("em-late", 3, "I2",
                        T0.plus(Duration.ofHours(3).plusMinutes(40))));
        assertTrue(late.recordedLate());
        assertFalse(late.planCompleted());
        assertEquals(RoutinePlanStatus.ABORTED.name(), late.planStatus());
        RoutineCheckItem lateItem = jdbc.queryForObject(
                "SELECT * FROM routine_check_item WHERE plan_id = ? AND item_code = 'I2'",
                (rs, n) -> new RoutineCheckItem(rs.getLong("id"), rs.getLong("plan_id"),
                        rs.getString("item_code"), rs.getInt("seq_no"),
                        rs.getTimestamp("completed_at").toInstant(), rs.getBoolean("recorded_late"),
                        rs.getString("request_id"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                inProgress.id());
        assertTrue(lateItem.recordedLate());

        // 被取消计划的回报 → 422
        ApiException cancelledReport = assertThrows(ApiException.class, () -> service.reportCheck(
                notStarted.id(), new PlanCheckReportRequest("em-ns-c", 1, "N1",
                        T0.plus(Duration.ofHours(1).plusMinutes(5)))));
        assertEquals("PLAN_CANCELLED", cancelledReport.getCode());

        // 结束紧急事件：版本到2，可另建新计划，被抢占计划不复活
        EmergencyView beforeEnd = service.getEmergency(emergency.emergencyId());
        assertEquals(1, beforeEnd.version());
        var ended = service.endEmergency(emergency.emergencyId(),
                new EmergencyEndRequest("em-end", 1, T0.plus(Duration.ofHours(4))));
        assertEquals(EmergencyStatus.ENDED.name(), ended.status());
        assertEquals(2, ended.version());
        assertEquals(RoutinePlanStatus.ABORTED.name(), service.getPlan(inProgress.id()).status());
        assertEquals(RoutinePlanStatus.CANCELLED.name(), service.getPlan(notStarted.id()).status());
        PlanPublishResponse fresh = service.publishPlan(planRequest("em-fresh", "RW05",
                T0.plus(Duration.ofHours(6)), T0.plus(Duration.ofHours(7)), 30, List.of("F1")));
        assertEquals(RoutinePlanStatus.APPROVED.name(), fresh.status());

        // 重复结束 → 422；错误版本 → 409
        ApiException endAgain = assertThrows(ApiException.class, () -> service.endEmergency(
                emergency.emergencyId(), new EmergencyEndRequest("em-end-2", 2,
                        T0.plus(Duration.ofHours(4).plusMinutes(5)))));
        assertEquals("EMERGENCY_NOT_ACTIVE", endAgain.getCode());
    }

    @Test
    void normalCompletionClosesOnlyPlanAndDoesNotPublishReopenNotam() {
        publishCalendar("cal-g", List.of());
        PlanPublishResponse plan = service.publishPlan(planRequest("cp-1", "RW06",
                T0.plus(Duration.ofHours(1)), T0.plus(Duration.ofHours(2)), 30, List.of("A", "B")));
        service.startPlan(plan.id(), new PlanStartRequest("cp-start", 1, 1, T0.plus(Duration.ofHours(1))));
        service.reportCheck(plan.id(), new PlanCheckReportRequest("cp-a", 2, "A",
                T0.plus(Duration.ofHours(1).plusMinutes(10))));
        PlanCheckReportResponse last = service.reportCheck(plan.id(),
                new PlanCheckReportRequest("cp-b", 3, "B", T0.plus(Duration.ofHours(1).plusMinutes(20))));
        assertTrue(last.planCompleted());
        assertEquals(RoutinePlanStatus.COMPLETED.name(), last.planStatus());
        assertEquals(4, last.planVersion());
        assertEquals(2, last.completedItemCount());

        Integer notams = jdbc.queryForObject("SELECT COUNT(*) FROM notam_record", Integer.class);
        assertEquals(0, notams, "例行正常完成不得取代恢复开放及通告发布流程");
        Integer emergencies = jdbc.queryForObject("SELECT COUNT(*) FROM runway_emergency", Integer.class);
        assertEquals(0, emergencies);
    }

    @Test
    void structuredFingerprintDistinguishesDelimitersAndCollections() {
        publishCalendar("cal-h", List.of());
        PlanPublishResponse first = service.publishPlan(planRequest("fp-1", "RW07",
                T0, T0.plus(Duration.ofMinutes(30)), 10, List.of("A|B", "C")));
        // 同键异参（集合元素不同，即便用 "|" 拼接文本相同）→ 409
        ApiException conflict = assertThrows(ApiException.class, () -> service.publishPlan(
                planRequest("fp-1", "RW07", T0, T0.plus(Duration.ofMinutes(30)), 10,
                        List.of("A", "B|C"))));
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());

        // 同键同参重放首次结果
        PlanPublishResponse replay = service.publishPlan(planRequest("fp-1", "RW07",
                T0, T0.plus(Duration.ofMinutes(30)), 10, List.of("A|B", "C")));
        assertEquals(first.id(), replay.id());
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM routine_plan WHERE runway_id = 'RW07'",
                Integer.class);
        assertEquals(1, count);
    }

    @Test
    void missingObjectsReturn404() {
        ApiException noPlan = assertThrows(ApiException.class, () -> service.getPlan(999_999));
        assertEquals(404, noPlan.getStatus().value());
        ApiException noEmergency = assertThrows(ApiException.class, () -> service.getEmergency(999_999));
        assertEquals(404, noEmergency.getStatus().value());

        publishCalendar("cal-i", List.of());
        PlanPublishResponse plan = service.publishPlan(planRequest("nf-1", "RW08",
                T0, T0.plus(Duration.ofMinutes(30)), 10, List.of("A")));
        ApiException noItem = assertThrows(ApiException.class, () -> service.reportCheck(plan.id(),
                new PlanCheckReportRequest("nf-x", 1, "GHOST", T0.plus(Duration.ofMinutes(5)))));
        assertEquals("CHECK_ITEM_NOT_FOUND", noItem.getCode());
        assertEquals(404, noItem.getStatus().value());

        ApiException noVersion = assertThrows(ApiException.class, () -> service.calendarImpact("RW08",
                T0, T0.plus(Duration.ofMinutes(30)), 10, 88));
        assertEquals("CALENDAR_VERSION_NOT_FOUND", noVersion.getCode());
    }

    @Test
    void calendarImpactQueryShowsConflictsAndEndpointTouchesAreClean() {
        publishCalendar("cal-j", List.of(
                flight("CA7", "RW10", T0.plus(Duration.ofMinutes(40)), T0.plus(Duration.ofMinutes(50)))));
        var impact = service.calendarImpact("RW10", T0.plus(Duration.ofMinutes(20)),
                T0.plus(Duration.ofMinutes(40)), 20, null);
        assertEquals(1, impact.calendarVersion());
        assertTrue(impact.conflicts().isEmpty(), "巡检 [20,40) 与占用 [40,50) 仅端点相接");

        var overlap = service.calendarImpact("RW10", T0.plus(Duration.ofMinutes(20)),
                T0.plus(Duration.ofMinutes(60)), 30, null);
        assertEquals(1, overlap.conflicts().size());
        assertEquals(10, overlap.conflicts().get(0).overlapMinutes());
    }

    @Test
    void concurrentStartVsEmergencyYieldsUniqueOccupation() throws Exception {
        publishCalendar("cal-k", List.of());
        PlanPublishResponse plan = service.publishPlan(planRequest("cc-1", "RW11",
                T0.plus(Duration.ofHours(1)), T0.plus(Duration.ofHours(2)), 30, List.of("A")));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<?>> futures = new java.util.ArrayList<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            final boolean starter = i % 2 == 0;
            futures.add(pool.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    if (starter) {
                        try {
                            service.startPlan(plan.id(), new PlanStartRequest("cc-start-" + idx, 1, 1,
                                    T0.plus(Duration.ofHours(1))));
                        } catch (ApiException expected) {
                            // 紧急接报先于开始时：版本冲突或计划已取消/待重审
                            assertTrue(List.of("VERSION_CONFLICT", "PLAN_NOT_STARTABLE",
                                            "PLAN_NEEDS_REVIEW", "EMERGENCY_ACTIVE", "CALENDAR_VERSION_CHANGED")
                                    .contains(expected.getCode()), expected.getCode());
                        }
                    } else {
                        try {
                            service.reportEmergency(new EmergencyReportRequest("cc-em-" + idx, "RW11",
                                    "并发险情", T0.plus(Duration.ofHours(1).plusSeconds(1))));
                        } catch (ApiException expected) {
                            assertEquals("EMERGENCY_ALREADY_ACTIVE", expected.getCode());
                        }
                    }
                } catch (Throwable t) {
                    unexpected.compareAndSet(null, t);
                }
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertNull(unexpected.get(), unexpected.get() == null ? "" : unexpected.get().getMessage());

        Integer activeEmergencies = jdbc.queryForObject(
                "SELECT COUNT(*) FROM runway_emergency WHERE runway_id = 'RW11' AND active_flag = 1",
                Integer.class);
        assertEquals(1, activeEmergencies, "同跑道并发后唯一进行中紧急事件");
        String status = jdbc.queryForObject("SELECT status FROM routine_plan WHERE id = ?",
                String.class, plan.id());
        assertTrue(status.equals("CANCELLED") || status.equals("ABORTED"),
                "开始与紧急接报竞争后计划必须是被抢占终态，实际: " + status);
        Integer livePlans = jdbc.queryForObject(
                "SELECT COUNT(*) FROM routine_plan WHERE runway_id = 'RW11' AND active_flag = 1",
                Integer.class);
        assertEquals(0, livePlans, "紧急关闭进行中跑道不得残留存活例行计划");
    }

    @Test
    void concurrentSameWindowPublishesAcceptExactlyOne() throws Exception {
        publishCalendar("cal-l", List.of());
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<?>> futures = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger success = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    service.publishPlan(planRequest("ccp-" + idx, "RW12",
                            T0.plus(Duration.ofHours(1)), T0.plus(Duration.ofHours(2)), 30,
                            List.of("A")));
                    success.incrementAndGet();
                } catch (ApiException expected) {
                    assertEquals("DUPLICATE_PLAN_WINDOW", expected.getCode());
                } catch (Throwable t) {
                    unexpected.compareAndSet(null, t);
                }
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertNull(unexpected.get(), unexpected.get() == null ? "" : unexpected.get().getMessage());
        assertEquals(1, success.get(), "同跑道同窗并发发布仅一个获准");
    }

    @Test
    void crossRunwayOperationsDoNotBlockEachOther() throws Exception {
        publishCalendar("cal-m", List.of());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Future<?> rwA = pool.submit(() -> {
            barrier.await(5, TimeUnit.SECONDS);
            PlanPublishResponse plan = service.publishPlan(planRequest("x-a", "RW20",
                    T0, T0.plus(Duration.ofMinutes(30)), 10, List.of("A")));
            service.reportEmergency(new EmergencyReportRequest("x-a-em", "RW20", "A跑道险情",
                    T0.plus(Duration.ofMinutes(5))));
            return plan;
        });
        Future<?> rwB = pool.submit(() -> {
            barrier.await(5, TimeUnit.SECONDS);
            PlanPublishResponse plan = service.publishPlan(planRequest("x-b", "RW21",
                    T0, T0.plus(Duration.ofMinutes(30)), 10, List.of("B1", "B2")));
            service.startPlan(plan.id(), new PlanStartRequest("x-b-start", 1, 1, T0));
            return service.reportCheck(plan.id(), new PlanCheckReportRequest("x-b-c", 2, "B1",
                    T0.plus(Duration.ofMinutes(8))));
        });
        rwA.get(30, TimeUnit.SECONDS);
        PlanCheckReportResponse bResult = (PlanCheckReportResponse) rwB.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(RoutinePlanStatus.CANCELLED.name(),
                service.listPlans("RW20").get(0).status());
        assertTrue(bResult.recordedLate() == false);
        assertEquals(RoutinePlanStatus.IN_PROGRESS.name(),
                service.getPlan(bResult.planId()).status(), "B跑道计划不受A跑道紧急关闭影响");
    }
}
