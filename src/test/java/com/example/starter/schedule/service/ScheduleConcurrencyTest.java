package com.example.starter.schedule.service;

import com.example.starter.schedule.AbstractIntegrationTest;
import com.example.starter.schedule.domain.ContactWindow;
import com.example.starter.schedule.web.dto.ReportRequestDto;
import com.example.starter.schedule.web.dto.ReportResponseDto;
import com.example.starter.schedule.web.dto.ScheduleRequestDto;
import com.example.starter.schedule.web.dto.ScheduleResponseDto;
import com.example.starter.schedule.model.ReportStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实多线程并发测试（H2 多连接、行锁/串行化锁），带超时，断言最终数据而非打印成功。
 */
class ScheduleConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private ReportService reportService;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        cleanAll();
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("并发同站同窗申请（不同 requestId）只允许一个成功，另一个被拒")
    void concurrentSameStationWindowOnlyOneSucceeds() throws Exception {
        long sat = createSatellite("SAT-A");
        long station = createStation("GS-1");
        ContactWindow w = window(sat, station, 2, 0, 2, 15);

        CyclicBarrier barrier = new CyclicBarrier(2);

        Future<ScheduleResponseDto> f1 = pool.submit(() -> {
            barrier.await(10, TimeUnit.SECONDS);
            return scheduleService.submit(
                    new ScheduleRequestDto("req-c1", "SAT-A", "GS-1", w.id(), 3, false));
        });
        Future<ScheduleResponseDto> f2 = pool.submit(() -> {
            barrier.await(10, TimeUnit.SECONDS);
            return scheduleService.submit(
                    new ScheduleRequestDto("req-c2", "SAT-A", "GS-1", w.id(), 3, false));
        });

        ScheduleResponseDto r1 = f1.get(30, TimeUnit.SECONDS);
        ScheduleResponseDto r2 = f2.get(30, TimeUnit.SECONDS);

        long scheduled = java.util.stream.Stream.of(r1, r2)
                .filter(r -> r.outcome().equals("SCHEDULED")).count();
        long rejected = java.util.stream.Stream.of(r1, r2)
                .filter(r -> r.outcome().equals("REJECTED")).count();
        assertThat(scheduled).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);

        Long activeEntries = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_entry WHERE station_id = ? AND window_id = ? AND status = 'SCHEDULED'",
                Long.class, station, w.id());
        assertThat(activeEntries).isEqualTo(1L);
        // 被拒绝申请不产生编排条目
        Long totalEntries = jdbc.queryForObject("SELECT COUNT(*) FROM schedule_entry", Long.class);
        assertThat(totalEntries).isEqualTo(1L);
    }

    @Test
    @DisplayName("并发同站内部重叠窗口：一端点相接场景之外严格重叠时恰好一成一拒")
    void concurrentOverlappingWindowsExactlyOneWinner() throws Exception {
        long satA = createSatellite("SAT-A");
        long satB = createSatellite("SAT-B");
        long station = createStation("GS-1");
        ContactWindow w1 = window(satA, station, 2, 0, 2, 15);
        ContactWindow w2 = window(satB, station, 2, 5, 2, 20);

        CyclicBarrier barrier = new CyclicBarrier(2);
        Future<ScheduleResponseDto> f1 = pool.submit(() -> {
            barrier.await(10, TimeUnit.SECONDS);
            return scheduleService.submit(
                    new ScheduleRequestDto("req-o1", "SAT-A", "GS-1", w1.id(), 3, false));
        });
        Future<ScheduleResponseDto> f2 = pool.submit(() -> {
            barrier.await(10, TimeUnit.SECONDS);
            return scheduleService.submit(
                    new ScheduleRequestDto("req-o2", "SAT-B", "GS-1", w2.id(), 3, false));
        });

        ScheduleResponseDto r1 = f1.get(30, TimeUnit.SECONDS);
        ScheduleResponseDto r2 = f2.get(30, TimeUnit.SECONDS);

        long scheduled = java.util.stream.Stream.of(r1, r2)
                .filter(r -> r.outcome().equals("SCHEDULED")).count();
        assertThat(scheduled).isEqualTo(1);
        Long activeEntries = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_entry WHERE status = 'SCHEDULED'", Long.class);
        assertThat(activeEntries).isEqualTo(1L);
    }

    @Test
    @DisplayName("漏报标记与迟到回报并发：形成唯一终态，事实与事件/回报一致")
    void missedMarkRacesWithLateReport() throws Exception {
        long sat = createSatellite("SAT-A");
        long station = createStation("GS-1");
        mutableClock.setInstant(Instant.parse("2026-09-25T00:00:00Z"));
        ContactWindow w = window(sat, station, 1, 0, 1, 10);
        scheduleService.submit(
                new ScheduleRequestDto("req-race", "SAT-A", "GS-1", w.id(), 3, false));

        // 窗口结束时刻已过，漏报扫描与回报同时发起
        mutableClock.setInstant(Instant.parse("2026-09-25T02:00:00Z"));
        CyclicBarrier barrier = new CyclicBarrier(2);

        Future<Integer> scanFuture = pool.submit(() -> {
            barrier.await(10, TimeUnit.SECONDS);
            return reportService.scanMissed();
        });
        Future<ReportResponseDto> reportFuture = pool.submit(() -> {
            barrier.await(10, TimeUnit.SECONDS);
            return reportService.submit("GS-1",
                    new ReportRequestDto(w.id(), ReportStatus.SUCCESS, "竞态回报"));
        });

        int missed = scanFuture.get(30, TimeUnit.SECONDS);
        ReportResponseDto report = reportFuture.get(30, TimeUnit.SECONDS);

        String finalStatus = jdbc.queryForObject(
                "SELECT status FROM schedule_entry WHERE request_id = ?",
                String.class, "req-race");
        Integer reportCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM contact_report WHERE station_id = ? AND window_id = ?",
                Integer.class, station, w.id());
        Integer lateEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM contact_report_event WHERE station_id = ? AND window_id = ? AND event_type = 'LATE_REPORT'",
                Integer.class, station, w.id());

        if (missed == 1) {
            // 漏报先成：迟到回报只记事件
            assertThat(finalStatus).isEqualTo("MISSED");
            assertThat(report.late()).isTrue();
            assertThat(reportCount).isZero();
            assertThat(lateEvents).isEqualTo(1);
        } else {
            // 回报先成：终态为已回报，条目不转漏报，也不产生迟到事件
            assertThat(missed).isZero();
            assertThat(finalStatus).isEqualTo("SCHEDULED");
            assertThat(report.late()).isFalse();
            assertThat(report.duplicate()).isFalse();
            assertThat(reportCount).isEqualTo(1);
            assertThat(lateEvents).isZero();
        }
    }
}
