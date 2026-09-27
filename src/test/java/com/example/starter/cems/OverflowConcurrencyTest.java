package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

import com.example.starter.cems.CemsTypes.HourlyReadingRequest;
import com.example.starter.cems.CemsTypes.OverflowHourEntry;
import com.example.starter.cems.CemsTypes.OverflowHoursRequest;
import com.example.starter.cems.CemsTypes.OverflowHoursResult;
import com.example.starter.cems.CemsTypes.OverflowPermitRequest;
import com.example.starter.cems.CemsTypes.OverflowReviewRequest;
import com.example.starter.cems.CemsTypes.OverflowReviewResult;
import com.example.starter.cems.CemsTypes.OverflowSettlementRequest;
import com.example.starter.cems.CemsTypes.OverflowSnapshotView;

/**
 * 雨季溢流 H2（MODE=MySQL）真实事务与并发边界测试：
 * 许可版本并发变更唯一受理、复核与日结竞争冻结一致、同键并发重放唯一受理。
 * 使用真实线程与 CyclicBarrier 协调并发，断言最终数据；不靠休眠或打印代替验证。
 */
@SpringBootTest
class OverflowConcurrencyTest {

    private static final String CODE = "OV-CC";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);

    @Autowired
    private CemsService cemsService;
    @Autowired
    private OverflowService service;
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

    private static LocalDateTime utc(int hour) {
        return LocalDateTime.of(2026, 9, 26, hour, 0);
    }

    @Test
    void concurrentPermitModifyWithSameExpectedVersionAcceptsOnlyOne() throws Exception {
        service.upsertPermit(new OverflowPermitRequest("p-init", CODE, 0, utc(0), utc(0).plusHours(24),
                new BigDecimal("5"), 3));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        try {
            Runnable task = () -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    service.upsertPermit(new OverflowPermitRequest("p-race-" + Thread.currentThread().getName(),
                            CODE, 1, utc(0), utc(0).plusHours(24), new BigDecimal("6"), 2));
                    succeeded.incrementAndGet();
                } catch (ConflictException e) {
                    conflicted.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };
            Future<?> f1 = pool.submit(task);
            Future<?> f2 = pool.submit(task);
            f1.get(20, TimeUnit.SECONDS);
            f2.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, succeeded.get(), "同一 expectedVersion 并发变更仅一个受理");
        assertEquals(1, conflicted.get(), "另一个必须 409 版本冲突");
        assertEquals(2, service.getPermit(CODE).version(), "成功变更只加 1 个版本");
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_permit WHERE outlet_code = ?", Long.class, CODE));
    }

    @Test
    void concurrentReviewAndSettleFreezeConsistentDualCaliberSnapshot() throws Exception {
        service.upsertPermit(new OverflowPermitRequest("p-1", CODE, 0, utc(0), utc(0).plusHours(24),
                new BigDecimal("5"), 3));
        service.reportHours(new OverflowHoursRequest("h-1", CODE,
                List.of(new OverflowHourEntry(utc(7), new BigDecimal("6"), true))));
        cemsService.reportHourly(new HourlyReadingRequest(CODE, utc(7), new BigDecimal("20")));
        cemsService.reportHourly(new HourlyReadingRequest(CODE, utc(8), new BigDecimal("40")));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<OverflowSnapshotView> settleFuture = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.settle(new OverflowSettlementRequest("cc-settle", CODE, DAY));
            });
            Future<OverflowReviewResult> reviewFuture = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.review(new OverflowReviewRequest("cc-review", CODE, DAY));
            });
            OverflowSnapshotView settled = settleFuture.get(20, TimeUnit.SECONDS);
            OverflowSnapshotView reviewed = reviewFuture.get(20, TimeUnit.SECONDS).snapshot();

            // 竞争冻结一致：两端返回同一双口径结果（全口径 30，正常口径 40）。
            assertNotNull(settled.allAvg());
            assertEquals(0, settled.allAvg().compareTo(reviewed.allAvg()));
            assertEquals(0, settled.normalAvg().compareTo(reviewed.normalAvg()));
            assertEquals(settled.normalCount(), reviewed.normalCount());
            assertEquals(settled.allCount(), reviewed.allCount());
            assertEquals(settled.frozenBy(), reviewed.frozenBy(), "先冻者为准，两端冻结来源一致");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_daily_snapshot WHERE outlet_code = ?", Long.class, CODE),
                "竞争冻结只产生一个日结快照");
        assertEquals(0, new BigDecimal("30").compareTo(service.getSnapshot(CODE, DAY).allAvg()));
        assertEquals(0, new BigDecimal("40").compareTo(service.getSnapshot(CODE, DAY).normalAvg()));
    }

    @Test
    void concurrentSameRequestIdReportHoursReplaysSingleResult() throws Exception {
        service.upsertPermit(new OverflowPermitRequest("p-1", CODE, 0, utc(0), utc(0).plusHours(24),
                new BigDecimal("5"), 3));
        List<OverflowHourEntry> entries = List.of(
                new OverflowHourEntry(utc(7), new BigDecimal("6"), true),
                new OverflowHourEntry(utc(8), new BigDecimal("7"), true));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<OverflowHoursResult> f1 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.reportHours(new OverflowHoursRequest("cc-hours", CODE, entries));
            });
            Future<OverflowHoursResult> f2 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.reportHours(new OverflowHoursRequest("cc-hours", CODE, entries));
            });
            OverflowHoursResult r1 = f1.get(20, TimeUnit.SECONDS);
            OverflowHoursResult r2 = f2.get(20, TimeUnit.SECONDS);
            assertEquals(r1.hours().size(), r2.hours().size());
            assertEquals(r1.hours().get(0).rainfallVersion(), r2.hours().get(0).rainfallVersion(),
                    "并发同键同参必须回放首次结果");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_hourly WHERE outlet_code = ?", Long.class, CODE));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'cc-hours'", Long.class));
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM overflow_hourly WHERE outlet_code = ? AND rainfall_version = 1",
                Long.class, CODE) == 2, "并发同键不得重复加雨量版本");
    }
}
