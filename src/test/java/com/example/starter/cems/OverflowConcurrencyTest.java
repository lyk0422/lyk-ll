package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

import com.example.starter.cems.CemsTypes.OverflowHourlyRequest;
import com.example.starter.cems.CemsTypes.OverflowPermitCreateRequest;
import com.example.starter.cems.CemsTypes.OverflowReviewRequest;
import com.example.starter.cems.CemsTypes.OverflowReviewResult;
import com.example.starter.cems.CemsTypes.RainfallReportRequest;

/**
 * 雨季溢流 H2（MODE=MySQL）真实事务/并发边界：
 * 标记复核与日结并发竞争只产生一份一致双口径冻结；同键并发幂等重放；雨量版本乐观并发唯一受理。
 */
@SpringBootTest
class OverflowConcurrencyTest {

    private static final String CODE = "OUT-OVCC";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 2);

    @Autowired
    private OverflowService overflow;
    @Autowired
    private CemsService cems;
    @Autowired
    private CemsClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        CemsTestSupport.clean(jdbc);
        clock.reset();
        clock.setFixed(CemsTestSupport.T0);
        CemsTestSupport.register(cems, CODE, "50", "10");
        overflow.createPermit(new OverflowPermitCreateRequest(
                "p-cc", CODE, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay(),
                new BigDecimal("5.0"), 10));
        // 一个正常排放小时（不溢流），保证 NORMAL 口径非空可比较。
        overflow.reportOverflowHour(new OverflowHourlyRequest(
                "h-cc-0", CODE, DAY.atTime(0, 0), new BigDecimal("100"), false));
        overflow.reportRainfall(new RainfallReportRequest(
                "r-cc-1", CODE, DAY.atTime(1, 0), new BigDecimal("8"), null));
        overflow.reportOverflowHour(new OverflowHourlyRequest(
                "h-cc-1", CODE, DAY.atTime(1, 0), new BigDecimal("200"), true));
        overflow.reportRainfall(new RainfallReportRequest(
                "r-cc-2", CODE, DAY.atTime(2, 0), new BigDecimal("8"), null));
        overflow.reportOverflowHour(new OverflowHourlyRequest(
                "h-cc-2", CODE, DAY.atTime(2, 0), new BigDecimal("300"), true));
    }

    @Test
    void concurrentReviewCompetitionProducesSingleConsistentFreeze() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<OverflowReviewResult> f1 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return overflow.review(new OverflowReviewRequest("cc-win", CODE, DAY));
            });
            Future<OverflowReviewResult> f2 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return overflow.review(new OverflowReviewRequest("cc-lose", CODE, DAY));
            });
            OverflowReviewResult r1 = f1.get(30, TimeUnit.SECONDS);
            OverflowReviewResult r2 = f2.get(30, TimeUnit.SECONDS);

            // 一个是首次冻结，另一个必须复用同一快照（replayed=true）。
            assertTrue(r1.replayed() ^ r2.replayed(), "竞争双方须恰好一方首次冻结、另一方复用");
            assertEquals(r1.frozenCompliantHours(), r2.frozenCompliantHours());
            assertEquals(0, r1.summary().normal().averageValue().compareTo(r2.summary().normal().averageValue()));
            assertEquals(0, r1.summary().all().averageValue().compareTo(r2.summary().all().averageValue()));
        } finally {
            pool.shutdownNow();
        }
        // 仅一份日结冻结与一组双口径快照。
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_daily_freeze WHERE outlet_code = ?", Long.class, CODE));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_daily_summary WHERE outlet_code = ?", Long.class, CODE));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_mark WHERE outlet_code = ? AND status='FROZEN_COMPLIANT'",
                Long.class, CODE));
    }

    @Test
    void concurrentSameReviewRequestIdBothReplaySingleResult() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<OverflowReviewResult> f1 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return overflow.review(new OverflowReviewRequest("cc-same", CODE, DAY));
            });
            Future<OverflowReviewResult> f2 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return overflow.review(new OverflowReviewRequest("cc-same", CODE, DAY));
            });
            OverflowReviewResult r1 = f1.get(30, TimeUnit.SECONDS);
            OverflowReviewResult r2 = f2.get(30, TimeUnit.SECONDS);
            assertEquals(r1.frozenCompliantHours(), r2.frozenCompliantHours());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id='cc-same'", Long.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_daily_freeze WHERE outlet_code = ?", Long.class, CODE));
    }

    @Test
    void concurrentRainfallCorrectionWithSameExpectedVersionAcceptsOnlyOne() throws Exception {
        LocalDateTime hour = DAY.atTime(3, 0);
        overflow.reportRainfall(new RainfallReportRequest("r-base", CODE, hour, new BigDecimal("8"), null));

        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                final String mm = i == 0 ? "7.0" : "6.0";
                final String rid = "r-race-" + i;
                pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        overflow.reportRainfall(new RainfallReportRequest(
                                rid, CODE, hour, new BigDecimal(mm), 1));
                        accepted.incrementAndGet();
                    } catch (ConflictException e) {
                        rejected.incrementAndGet();
                    }
                    return null;
                });
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertEquals(1, accepted.get(), "相同 expectedVersion 并发更正只允许一个落版本 2");
        assertEquals(threads - 1, rejected.get(), "其余并发更正必须 409");
        assertEquals(2, ((Number) jdbc.queryForMap(
                "SELECT MAX(version) v FROM rainfall_reading WHERE outlet_code = ? AND hour_utc = ?",
                CODE, java.sql.Timestamp.valueOf(hour)).get("v")).intValue());
        // 仅生成 v1、v2 两个雨量版本，失败的并发更正不占版本号。
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rainfall_reading WHERE outlet_code = ? AND hour_utc = ?",
                Long.class, CODE, java.sql.Timestamp.valueOf(hour)));
    }

    @Test
    void businessRejectionRollsBackFreezeAndMarksRemainReady() {
        // 再加一个缺雨量溢流小时，使当日出现 PENDING。
        overflow.reportOverflowHour(new OverflowHourlyRequest(
                "h-pending", CODE, DAY.atTime(4, 0), new BigDecimal("260"), true));
        assertThrows(BusinessRejectException.class,
                () -> overflow.review(new OverflowReviewRequest("cc-rollback", CODE, DAY)));
        // 回滚后：无冻结行、无快照、READY 标记不被冻结。
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_daily_freeze WHERE outlet_code = ?", Long.class, CODE));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_daily_summary WHERE outlet_code = ?", Long.class, CODE));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_mark WHERE outlet_code = ? AND status='FROZEN_COMPLIANT'",
                Long.class, CODE));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_mark WHERE outlet_code = ? AND status='READY'",
                Long.class, CODE));
    }
}
