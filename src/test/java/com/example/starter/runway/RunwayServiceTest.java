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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跑道服务业务/H2 集成测试：覆盖主流程、失败分支、并发与幂等边界。
 */
@SpringBootTest
class RunwayServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-26T01:00:00Z");

    @Autowired
    private RunwayService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM notam_record");
        jdbc.update("DELETE FROM assessment_record");
        jdbc.update("DELETE FROM inspection_record");
        jdbc.update("DELETE FROM runway_event");
        jdbc.update("DELETE FROM weather_reading");
    }

    private ReportResponse report(String requestId, String runway) {
        return service.report(new ReportRequest(requestId, runway, EventType.FOD, T0));
    }

    @Test
    void mainFlowReportInspectAssessReopenWithNotams() {
        ReportResponse reported = report("req-1", "RW01");
        assertEquals(EventStatus.CLOSED.name(), reported.status());
        assertEquals(T0, reported.closedAt());
        assertEquals(T0.plus(Duration.ofMinutes(15)), reported.inspectionDeadline());
        assertTrue(reported.notamNo().startsWith("NTM-C-"));

        InspectionResponse inspection = service.startInspection(reported.eventId(),
                new InspectionRequest(T0.plus(Duration.ofMinutes(5)), 2));
        assertFalse(inspection.late());
        assertFalse(inspection.escalated());
        assertEquals(EventStatus.INSPECTING.name(), inspection.eventStatus());

        AssessmentResponse assessed = service.assess(reported.eventId(),
                new AssessmentRequest("assess-1", new BigDecimal("0.50"), AssessmentType.CLEAR_TO_REOPEN,
                        T0.plus(Duration.ofMinutes(8))));
        assertTrue(assessed.passed());

        ReopenResponse reopened = service.reopen(reported.eventId(),
                new ReopenRequest("reopen-1", T0.plus(Duration.ofMinutes(10))));
        assertEquals(EventStatus.REOPENED.name(), reopened.status());
        assertTrue(reopened.notamNo().startsWith("NTM-R-"));

        List<NotamView> notams = service.getNotams(reported.eventId());
        assertEquals(2, notams.size());
        assertEquals(NotamAction.CLOSE.name(), notams.get(0).action());
        assertEquals(NotamAction.REOPEN.name(), notams.get(1).action());

        EventDetailResponse detail = service.getDetail(reported.eventId());
        List<String> types = detail.timeline().stream().map(TimelineEntry::type).toList();
        assertTrue(types.contains("REPORTED"));
        assertTrue(types.contains("CLOSED_NOTAM"));
        assertTrue(types.contains("INSPECTION_STARTED"));
        assertTrue(types.contains("ASSESSED"));
        assertTrue(types.contains("REOPEN_NOTAM"));
        assertTrue(types.contains("REOPENED"));
    }

    @Test
    void exactlyFifteenMinutesIsLateAndEscalates() {
        ReportResponse reported = report("req-late", "RW02");

        InspectionResponse exactly = service.startInspection(reported.eventId(),
                new InspectionRequest(reported.inspectionDeadline(), 2));
        assertTrue(exactly.late(), "恰满15分钟须判定迟延");
        assertTrue(exactly.escalated(), "迟延须升级");

        EventDetailResponse detail = service.getDetail(reported.eventId());
        assertTrue(detail.escalated());
        assertTrue(detail.timeline().stream().anyMatch(t -> t.type().equals("ESCALATED")));
    }

    @Test
    void fourteenMinutesFiftyNineSecondsIsNotLate() {
        ReportResponse reported = report("req-ontime", "RW03");
        InspectionResponse beforeDeadline = service.startInspection(reported.eventId(),
                new InspectionRequest(reported.inspectionDeadline().minusSeconds(1), 2));
        assertFalse(beforeDeadline.late());
        assertFalse(beforeDeadline.escalated());
    }

    @Test
    void frictionExactlyThresholdPassesButBelowKeepsClosed() {
        ReportResponse reported = report("req-friction", "RW04");
        service.startInspection(reported.eventId(), new InspectionRequest(T0.plus(Duration.ofMinutes(3)), 2));

        AssessmentResponse exactly = service.assess(reported.eventId(),
                new AssessmentRequest("a-035", new BigDecimal("0.35"), AssessmentType.CLEAR_TO_REOPEN,
                        T0.plus(Duration.ofMinutes(6))));
        assertTrue(exactly.passed(), "恰0.35判定合格");
        assertDoesNotThrow(() -> service.reopen(reported.eventId(),
                new ReopenRequest("r-035", T0.plus(Duration.ofMinutes(9)))));

        ReportResponse other = report("req-friction-2", "RW04B");
        service.startInspection(other.eventId(), new InspectionRequest(T0.plus(Duration.ofMinutes(3)), 2));
        service.assess(other.eventId(),
                new AssessmentRequest("a-034", new BigDecimal("0.34"), AssessmentType.CLEAR_TO_REOPEN,
                        T0.plus(Duration.ofMinutes(6))));
        ApiException ex = assertThrows(ApiException.class, () -> service.reopen(other.eventId(),
                new ReopenRequest("r-034", T0.plus(Duration.ofMinutes(9)))));
        assertEquals("FRICTION_BELOW_THRESHOLD", ex.getCode());
        assertEquals(EventStatus.ASSESSED.name(), service.getDetail(other.eventId()).status());
    }

    @Test
    void repairRequiresRetestPassBeforeReopen() {
        ReportResponse reported = report("req-repair", "RW05");
        service.startInspection(reported.eventId(), new InspectionRequest(T0.plus(Duration.ofMinutes(3)), 2));

        service.assess(reported.eventId(),
                new AssessmentRequest("a-repair", new BigDecimal("0.20"), AssessmentType.REPAIR_REQUIRED,
                        T0.plus(Duration.ofMinutes(6))));
        ApiException blocked = assertThrows(ApiException.class, () -> service.reopen(reported.eventId(),
                new ReopenRequest("r-blocked", T0.plus(Duration.ofMinutes(9)))));
        assertEquals("REPAIR_RETEST_REQUIRED", blocked.getCode());

        // 修复期间摩擦仍不合格：持续关闭
        service.assess(reported.eventId(),
                new AssessmentRequest("a-repair-2", new BigDecimal("0.30"), AssessmentType.REPAIR_REQUIRED,
                        T0.plus(Duration.ofMinutes(20))));
        assertThrows(ApiException.class, () -> service.reopen(reported.eventId(),
                new ReopenRequest("r-blocked-2", T0.plus(Duration.ofMinutes(22)))));

        AssessmentResponse retest = service.assess(reported.eventId(),
                new AssessmentRequest("a-retest", new BigDecimal("0.40"), AssessmentType.CLEAR_TO_REOPEN,
                        T0.plus(Duration.ofMinutes(40))));
        assertTrue(retest.passed());
        assertTrue(retest.retest());
        ReopenResponse reopened = service.reopen(reported.eventId(),
                new ReopenRequest("r-ok", T0.plus(Duration.ofMinutes(45))));
        assertEquals(EventStatus.REOPENED.name(), reopened.status());

        List<FrictionPoint> trajectory = service.getFrictionTrajectory(reported.eventId());
        assertEquals(3, trajectory.size());
        assertFalse(trajectory.get(0).passed());
        assertTrue(trajectory.get(2).passed());
        assertTrue(trajectory.get(2).retest());
    }

    @Test
    void lowVisibilityRequiresDualInspectors() {
        ReportResponse reported = report("req-weather", "RW06");
        service.addWeatherReading(new WeatherRequest("RW06", 400, T0.plus(Duration.ofMinutes(2))));

        ApiException single = assertThrows(ApiException.class, () -> service.startInspection(
                reported.eventId(), new InspectionRequest(T0.plus(Duration.ofMinutes(4)), 1)));
        assertEquals("LOW_VISIBILITY_REQUIRES_DUAL", single.getCode());
        Integer singleCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inspection_record WHERE event_id = ?", Integer.class, reported.eventId());
        assertEquals(0, singleCount, "单人记录不生效，不入库");

        InspectionResponse dual = service.startInspection(reported.eventId(),
                new InspectionRequest(T0.plus(Duration.ofMinutes(5)), 2));
        assertFalse(dual.recordedAfterArchive());

        // 能见度正常时单人巡检允许执行（须在15分钟时限内）
        service.addWeatherReading(new WeatherRequest("RW06C", 800, T0));
        ReportResponse reported2 = report("req-weather-2", "RW06C");
        InspectionResponse singleFine = service.startInspection(reported2.eventId(),
                new InspectionRequest(T0.plus(Duration.ofMinutes(10)), 1));
        assertFalse(singleFine.late());
    }

    @Test
    void reopenAndNotamAreAtomicInDatabase() {
        ReportResponse reported = report("req-atomic", "RW07");
        service.startInspection(reported.eventId(), new InspectionRequest(T0.plus(Duration.ofMinutes(3)), 2));
        service.assess(reported.eventId(),
                new AssessmentRequest("a-atomic", new BigDecimal("0.60"), AssessmentType.CLEAR_TO_REOPEN,
                        T0.plus(Duration.ofMinutes(6))));
        service.reopen(reported.eventId(), new ReopenRequest("r-atomic", T0.plus(Duration.ofMinutes(9))));

        String status = jdbc.queryForObject("SELECT status FROM runway_event WHERE id = ?",
                String.class, reported.eventId());
        Integer notamCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notam_record WHERE event_id = ? AND action = 'REOPEN'",
                Integer.class, reported.eventId());
        Integer activeFlags = jdbc.queryForObject(
                "SELECT COUNT(*) FROM runway_event WHERE runway_id = 'RW07' AND active_flag = 1",
                Integer.class);
        assertEquals("REOPENED", status);
        assertEquals(1, notamCount, "恢复通告必须存在");
        assertEquals(0, activeFlags, "恢复后须释放进行中占位");
    }

    @Test
    void concurrentReportsOnlyOneActiveEventAccepted() throws Exception {
        int threads = 8;
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
                    service.report(new ReportRequest("cc-req-" + idx, "RW08", EventType.PAVEMENT_DAMAGE, T0));
                    success.incrementAndGet();
                } catch (ApiException e) {
                    if ("RUNWAY_ACTIVE_EVENT_EXISTS".equals(e.getCode())) {
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
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, success.get(), "并发接报仅唯一受理");
        assertEquals(threads - 1, conflict.get());
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM runway_event WHERE runway_id = 'RW08' AND active_flag = 1",
                Integer.class);
        assertEquals(1, activeCount);
    }

    @Test
    void idempotentReplayReturnsFirstResultAndConflictOnDifferentParams() {
        ReportResponse first = report("idem-req", "RW09");
        ReportResponse replay = report("idem-req", "RW09");
        assertEquals(first.eventId(), replay.eventId());
        assertEquals(first.notamNo(), replay.notamNo());
        Integer eventCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM runway_event WHERE request_id = 'idem-req'", Integer.class);
        assertEquals(1, eventCount);

        ApiException conflict = assertThrows(ApiException.class, () -> service.report(
                new ReportRequest("idem-req", "RW09", EventType.PAVEMENT_DAMAGE, T0)));
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());

        // 评估与恢复同样幂等
        service.startInspection(first.eventId(), new InspectionRequest(T0.plus(Duration.ofMinutes(3)), 2));
        AssessmentRequest sameAssess = new AssessmentRequest("idem-assess", new BigDecimal("0.55"),
                AssessmentType.CLEAR_TO_REOPEN, T0.plus(Duration.ofMinutes(6)));
        AssessmentResponse a1 = service.assess(first.eventId(), sameAssess);
        AssessmentResponse a2 = service.assess(first.eventId(), sameAssess);
        assertEquals(a1.assessmentId(), a2.assessmentId());
        assertThrows(ApiException.class, () -> service.assess(first.eventId(),
                new AssessmentRequest("idem-assess", new BigDecimal("0.56"),
                        AssessmentType.CLEAR_TO_REOPEN, T0.plus(Duration.ofMinutes(6)))));

        ReopenRequest sameReopen = new ReopenRequest("idem-reopen", T0.plus(Duration.ofMinutes(10)));
        assertEquals(service.reopen(first.eventId(), sameReopen).notamNo(),
                service.reopen(first.eventId(), sameReopen).notamNo());
    }

    @Test
    void archiveIsTerminalAndRepeatedArchiveIdempotent() {
        ReportResponse reported = report("req-archive", "RW10");
        service.startInspection(reported.eventId(), new InspectionRequest(T0.plus(Duration.ofMinutes(3)), 2));
        service.assess(reported.eventId(),
                new AssessmentRequest("a-archive", new BigDecimal("0.50"), AssessmentType.CLEAR_TO_REOPEN,
                        T0.plus(Duration.ofMinutes(6))));
        service.reopen(reported.eventId(), new ReopenRequest("r-archive", T0.plus(Duration.ofMinutes(9))));

        ArchiveResponse archived = service.archive(reported.eventId());
        assertFalse(archived.alreadyArchived());
        ArchiveResponse again = service.archive(reported.eventId());
        assertTrue(again.alreadyArchived(), "重复归档返回幂等成功");

        // 归档后评估/恢复不再受理
        assertThrows(ApiException.class, () -> service.assess(reported.eventId(),
                new AssessmentRequest("a-late", new BigDecimal("0.50"), AssessmentType.CLEAR_TO_REOPEN,
                        T0.plus(Duration.ofMinutes(30)))));
        assertThrows(ApiException.class, () -> service.reopen(reported.eventId(),
                new ReopenRequest("r-late", T0.plus(Duration.ofMinutes(30)))));

        // 归档后迟到巡检只记迟到事件，状态不变
        InspectionResponse late = service.startInspection(reported.eventId(),
                new InspectionRequest(T0.plus(Duration.ofMinutes(35)), 2));
        assertTrue(late.recordedAfterArchive());
        assertEquals(EventStatus.ARCHIVED.name(), late.eventStatus());
        Integer lateAfterArchiveCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inspection_record WHERE event_id = ? AND recorded_after_archive = TRUE",
                Integer.class, reported.eventId());
        assertEquals(1, lateAfterArchiveCount);
        assertEquals(EventStatus.ARCHIVED.name(), service.getDetail(reported.eventId()).status());
    }

    @Test
    void cannotReopenWithoutAssessment() {
        ReportResponse reported = report("req-noassess", "RW11");
        ApiException ex = assertThrows(ApiException.class, () -> service.reopen(reported.eventId(),
                new ReopenRequest("r-noassess", T0.plus(Duration.ofMinutes(5)))));
        assertEquals("ASSESSMENT_REQUIRED", ex.getCode());
    }
}
