package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.cems.OverflowTypes.HourReportRequest;
import com.example.starter.cems.OverflowTypes.HourReportResult;
import com.example.starter.cems.OverflowTypes.OverflowHourItem;
import com.example.starter.cems.OverflowTypes.PermitUpsertRequest;
import com.example.starter.cems.OverflowTypes.PermitView;
import com.example.starter.cems.OverflowTypes.RainfallCorrectionRequest;
import com.example.starter.cems.OverflowTypes.RainfallCorrectionResult;
import com.example.starter.cems.OverflowTypes.ReviewFreezeRequest;
import com.example.starter.cems.OverflowTypes.ReviewView;

/**
 * 溢流 H2（MODE=MySQL）真实事务与并发边界测试：
 * 同键并发回放唯一受理、同 expectedVersion 并发新增许可/更正雨量唯一受理、
 * 日结冻结与小时标记竞争时双口径快照一致。真实线程＋CyclicBarrier 协调，断言最终数据。
 */
@SpringBootTest
class OverflowConcurrencyTest {

    private static final String CODE = "OUT-OFC";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);

    @Autowired
    private OverflowService service;
    @Autowired
    private CemsService cemsService;
    @Autowired
    private CemsClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        CemsTestSupport.clean(jdbc);
        clock.reset();
        clock.setFixed(CemsTestSupport.T0);
        CemsTestSupport.register(cemsService, CODE, "50", "10");
    }

    private static Instant utc(String text) {
        return Instant.parse(text);
    }

    private static OverflowHourItem item(String hourUtc, String rainfall, boolean overflow, String value) {
        return new OverflowHourItem(utc(hourUtc),
                rainfall == null ? null : new BigDecimal(rainfall), overflow, new BigDecimal(value));
    }

    private void permit() {
        service.upsertPermit(new PermitUpsertRequest("cc-permit", CODE, 0,
                utc("2026-09-26T00:00:00Z"), utc("2026-09-27T00:00:00Z"),
                new BigDecimal("5"), 10));
    }

    @Test
    void concurrentSameRequestIdHourReportReplaysSingleResult() throws Exception {
        permit();
        HourReportRequest req = new HourReportRequest("cc-hours", CODE, List.of(
                item("2026-09-26T08:00:00Z", "6", true, "20"),
                item("2026-09-26T09:00:00Z", "6", true, "30")));
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<HourReportResult> f1 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.reportHours(req);
            });
            Future<HourReportResult> f2 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.reportHours(req);
            });
            HourReportResult r1 = f1.get(20, TimeUnit.SECONDS);
            HourReportResult r2 = f2.get(20, TimeUnit.SECONDS);
            assertEquals(r1.reported(), r2.reported(), "并发同键同参必须回放首次结果");
            assertEquals(r1.hours().get(0).markStatus(), r2.hours().get(0).markStatus());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_hour WHERE outlet_code = ?", Long.class, CODE));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'cc-hours'", Long.class));
    }

    @Test
    void concurrentPermitUpsertWithSameExpectedVersionAcceptsOnlyOne() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            Runnable task = () -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                String tag = Thread.currentThread().getName();
                try {
                    service.upsertPermit(new PermitUpsertRequest("cc-p-" + tag, CODE, 0,
                            utc("2026-09-26T00:00:00Z"), utc("2026-09-27T00:00:00Z"),
                            new BigDecimal("5"), 10));
                    accepted.incrementAndGet();
                } catch (ConflictException e) {
                    rejected.incrementAndGet();
                }
            };
            Future<?> f1 = pool.submit(task);
            Future<?> f2 = pool.submit(task);
            f1.get(20, TimeUnit.SECONDS);
            f2.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, accepted.get(), "同 expectedVersion 并发新增许可仅一个受理");
        assertEquals(1, rejected.get(), "另一并发必须 409 冲突");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_permit WHERE outlet_code = ?", Long.class, CODE));
        assertEquals(1, service.getCurrentPermit(CODE).version());
    }

    @Test
    void concurrentRainfallCorrectionsWithSameExpectedVersionAcceptOnlyOne() throws Exception {
        permit();
        service.reportHours(new HourReportRequest("cc-h", CODE,
                List.of(item("2026-09-26T08:00:00Z", null, true, "20"))));
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            Runnable task = () -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                String tag = Thread.currentThread().getName();
                try {
                    RainfallCorrectionResult r = service.correctRainfall(new RainfallCorrectionRequest(
                            "cc-c-" + tag, CODE, utc("2026-09-26T08:00:00Z"),
                            new BigDecimal("6"), 1));
                    assertEquals(2, r.rainfallVersion());
                    accepted.incrementAndGet();
                } catch (ConflictException e) {
                    rejected.incrementAndGet();
                }
            };
            Future<?> f1 = pool.submit(task);
            Future<?> f2 = pool.submit(task);
            f1.get(20, TimeUnit.SECONDS);
            f2.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, accepted.get(), "同雨量版本并发更正仅一个受理");
        assertEquals(1, rejected.get());
        assertEquals(2, jdbc.queryForObject(
                "SELECT rainfall_version FROM overflow_counter WHERE outlet_code = ?",
                Integer.class, CODE));
        assertEquals("COMPLIANT", jdbc.queryForObject(
                "SELECT mark_status FROM overflow_hour WHERE outlet_code = ?", String.class, CODE));
    }

    @Test
    void concurrentFreezeAndHourReportYieldConsistentDualCaliberSnapshot() throws Exception {
        permit();
        // 预置：08 时普通小时（10）、09 时合规溢流（20）
        service.reportHours(new HourReportRequest("cc-pre", CODE, List.of(
                item("2026-09-26T08:00:00Z", "1", false, "10"),
                item("2026-09-26T09:00:00Z", "6", true, "20"))));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 竞争：补报 10 时合规溢流（30） vs 当日复核冻结
            Future<HourReportResult> reportFuture = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.reportHours(new HourReportRequest("cc-race-report", CODE,
                        List.of(item("2026-09-26T10:00:00Z", "6", true, "30"))));
            });
            Future<ReviewView> freezeFuture = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.freezeReview(new ReviewFreezeRequest("cc-race-freeze", CODE, DAY));
            });
            assertEquals(1, reportFuture.get(20, TimeUnit.SECONDS).reported());
            ReviewView review = freezeFuture.get(20, TimeUnit.SECONDS);

            // 无论谁先谁后，冻结的双口径结果必须与某一一致快照完全相等（序列化语义）
            boolean freezeFirst = review.allCount() == 2 && review.normalCount() == 1
                    && review.missingCount() == 22
                    && review.allAvg().compareTo(new BigDecimal("15.0000")) == 0
                    && review.normalAvg().compareTo(new BigDecimal("10.0000")) == 0;
            boolean reportFirst = review.allCount() == 3 && review.normalCount() == 1
                    && review.missingCount() == 21
                    && review.allAvg().compareTo(new BigDecimal("20.0000")) == 0
                    && review.normalAvg().compareTo(new BigDecimal("10.0000")) == 0;
            assertTrue(freezeFirst || reportFirst,
                    "冻结快照必须等于竞争序列化后的某一一致状态，实际: all=" + review.allAvg()
                            + "/" + review.allCount() + " normal=" + review.normalAvg()
                            + "/" + review.normalCount() + " missing=" + review.missingCount());
            assertEquals(1, review.permitVersion());
            assertNotNull(review.frozenAt());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(3L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_hour WHERE outlet_code = ?", Long.class, CODE));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_review WHERE outlet_code = ?", Long.class, CODE));
    }
}
