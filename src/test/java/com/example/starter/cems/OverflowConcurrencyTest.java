package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

import com.example.starter.cems.OverflowTypes.CreatePermitRequest;
import com.example.starter.cems.OverflowTypes.DailySettlementRequest;
import com.example.starter.cems.OverflowTypes.DailySettlementView;
import com.example.starter.cems.OverflowTypes.FreezeResult;
import com.example.starter.cems.OverflowTypes.HourDataRequest;
import com.example.starter.cems.OverflowTypes.HourView;
import com.example.starter.cems.OverflowTypes.ReviewFreezeRequest;
import com.example.starter.cems.OverflowTypes.UpdatePermitRequest;

/**
 * 雨季溢流域 H2（MODE=MySQL）真实事务与并发边界测试：
 * 同键并发小时上报唯一受理、竞争复核冻结结果一致、expectedVersion 并发修改唯一成功、
 * 并发日结唯一快照且双口径一致、CHECK 约束拒绝非法分母。
 * 真实线程 + CyclicBarrier 协调，断言最终数据，不靠休眠或打印代替验证。
 */
@SpringBootTest
class OverflowConcurrencyTest {

    private static final String CODE = "OUT-OVCC";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);
    private static final LocalDateTime WIN_START = LocalDateTime.of(2026, 9, 26, 0, 0);
    private static final LocalDateTime WIN_END = LocalDateTime.of(2026, 9, 27, 0, 0);
    private static final LocalDateTime FREEZE_AT = LocalDateTime.of(2026, 9, 26, 12, 0);

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
        clock.resetUtc();
        clock.setFixed(CemsTestSupport.T0);
        clock.setFixedUtc(FREEZE_AT);
        CemsTestSupport.register(cems, CODE, "50", "10");
        overflow.createPermit(new CreatePermitRequest(
                "cc-permit", CODE, WIN_START, WIN_END, new BigDecimal("10.00"), 2));
    }

    private HourDataRequest hourReq(int h, String requestId) {
        return new HourDataRequest(requestId, CODE, LocalDateTime.of(2026, 9, 26, h, 0),
                new BigDecimal("9"), new BigDecimal("11"), true);
    }

    @Test
    void concurrentSameRequestHourReportProducesSingleRecordAndSameResult() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<HourView> f1 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return overflow.reportHour(hourReq(8, "cc-hour"));
            });
            Future<HourView> f2 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return overflow.reportHour(hourReq(8, "cc-hour"));
            });
            HourView r1 = f1.get(20, TimeUnit.SECONDS);
            HourView r2 = f2.get(20, TimeUnit.SECONDS);
            assertEquals("COMPLIANT", r1.overflowStatus());
            assertEquals(r1.overflowStatus(), r2.overflowStatus());
            assertEquals(r1.hourUtc(), r2.hourUtc());
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM overflow_hour_record WHERE outlet_code = ?", Long.class, CODE));
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'cc-hour'", Long.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void competingFreezeWithSameKeyAndParamsProducesOneConsistentFrozenResult() throws Exception {
        overflow.reportHour(hourReq(9, "cc-pre"));
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<FreezeResult> f1 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return overflow.reviewFreeze(new ReviewFreezeRequest(
                        "cc-freeze", CODE, LocalDateTime.of(2026, 9, 26, 9, 0), FREEZE_AT));
            });
            Future<FreezeResult> f2 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return overflow.reviewFreeze(new ReviewFreezeRequest(
                        "cc-freeze", CODE, LocalDateTime.of(2026, 9, 26, 9, 0), FREEZE_AT));
            });
            FreezeResult r1 = f1.get(20, TimeUnit.SECONDS);
            FreezeResult r2 = f2.get(20, TimeUnit.SECONDS);
            assertEquals(r1.permitVersionFrozen(), r2.permitVersionFrozen());
            assertEquals(r1.rainfallVersionFrozen(), r2.rainfallVersionFrozen());
            assertEquals(r1.frozenAtUtc(), r2.frozenAtUtc());
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT * FROM overflow_hour_record WHERE outlet_code = ? AND hour_utc = ?",
                    CODE, java.sql.Timestamp.valueOf(LocalDateTime.of(2026, 9, 26, 9, 0)));
            assertNotNull(row.get("frozen_at"));
            assertEquals(1, ((Number) row.get("permit_version_frozen")).intValue());
            assertEquals(1, ((Number) row.get("rainfall_version_frozen")).intValue());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentPermitUpdatesWithSameExpectedVersionAcceptOnlyOne() throws Exception {
        int threads = 3;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        overflow.updatePermit(new UpdatePermitRequest(
                                "cc-update-" + idx, CODE, 1, WIN_START, WIN_END,
                                new BigDecimal(String.valueOf(11 + idx)), 3));
                        accepted.incrementAndGet();
                    } catch (ConflictException e) {
                        conflicted.incrementAndGet();
                    }
                    return null;
                });
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        }
        assertEquals(1, accepted.get(), "expectedVersion=1 的并发修改只能成功一个");
        assertEquals(threads - 1, conflicted.get(), "其余并发修改必须 409");
        assertEquals(2, overflow.listPermits(CODE).size(), "只允许产生一个新版本（v2）");
        assertEquals("ACTIVE", overflow.listPermits(CODE).get(1).status());
    }

    @Test
    void competingFreezeWithDifferentKeysStillConvergesToOneFrozenEvidence() throws Exception {
        overflow.reportHour(hourReq(10, "cc-pre2"));
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 不同 requestId、甚至不同精确时刻并发竞争同一小时：只能有一个冻结事实，双方读到一致证据。
            Future<FreezeResult> f1 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return overflow.reviewFreeze(new ReviewFreezeRequest(
                        "cc-freeze-a", CODE, LocalDateTime.of(2026, 9, 26, 10, 0), FREEZE_AT));
            });
            Future<FreezeResult> f2 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return overflow.reviewFreeze(new ReviewFreezeRequest(
                        "cc-freeze-b", CODE, LocalDateTime.of(2026, 9, 26, 10, 0),
                        FREEZE_AT.plusMinutes(5)));
            });
            FreezeResult r1 = f1.get(20, TimeUnit.SECONDS);
            FreezeResult r2 = f2.get(20, TimeUnit.SECONDS);
            assertEquals(r1.frozenAtUtc(), r2.frozenAtUtc(), "竞争冻结必须收敛到首个冻结时刻");
            assertEquals(r1.permitVersionFrozen(), r2.permitVersionFrozen());
            assertEquals(r1.rainfallVersionFrozen(), r2.rainfallVersionFrozen());
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM overflow_hour_record WHERE outlet_code = ? AND frozen_at IS NOT NULL",
                    Long.class, CODE));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDailySettlementCreatesOneSnapshotWithSameBasisResults() throws Exception {
        overflow.reportHour(new HourDataRequest("cc-s8", CODE,
                LocalDateTime.of(2026, 9, 26, 8, 0), new BigDecimal("10"), new BigDecimal("0"), false));
        overflow.reportHour(new HourDataRequest("cc-s9", CODE,
                LocalDateTime.of(2026, 9, 26, 9, 0), new BigDecimal("100"), new BigDecimal("11"), true));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<DailySettlementView> f1 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return overflow.settleDaily(new DailySettlementRequest("cc-settle-1", CODE, DAY));
            });
            Future<DailySettlementView> f2 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return overflow.settleDaily(new DailySettlementRequest("cc-settle-2", CODE, DAY));
            });
            DailySettlementView r1 = f1.get(20, TimeUnit.SECONDS);
            DailySettlementView r2 = f2.get(20, TimeUnit.SECONDS);
            assertEquals(r1.allAvg(), r2.allAvg(), "竞争日结必须冻结出一致双口径结果");
            assertEquals(r1.normalAvg(), r2.normalAvg());
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM overflow_daily_settlement WHERE outlet_code = ?", Long.class, CODE));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void settlementWithZeroDenominatorIsRejectedByCheckConstraint() {
        // 数据库不变量：日结 all_denominator 必须 > 0，杜绝“空集合零均值快照”。
        assertThrows(Exception.class, () -> jdbc.update(
                "INSERT INTO overflow_daily_settlement(outlet_code, day_utc, all_avg, normal_denominator, "
                        + "all_denominator, requested_hours, present_count, excluded_count, pending_count, "
                        + "missing_point_count, settled_at, created_at) VALUES (?, ?, 0, 0, 0, 24, 0, 0, 0, 24, ?, ?)",
                CODE, java.sql.Date.valueOf(DAY),
                java.sql.Timestamp.valueOf(FREEZE_AT), java.sql.Timestamp.valueOf(FREEZE_AT)));
    }
}
