package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.cems.CemsTypes.ConfirmOrderRequest;
import com.example.starter.cems.CemsTypes.DailyEvalRequest;
import com.example.starter.cems.CemsTypes.DailyEvalResult;
import com.example.starter.cems.CemsTypes.HourlyReadingRequest;
import com.example.starter.cems.CemsTypes.ReleaseRestrictionRequest;
import com.example.starter.cems.CemsTypes.ReleaseResult;

/**
 * H2（MODE=MySQL）真实事务与并发边界测试：
 * 同键并发重放唯一受理、执行中限排令唯一占槽、生效必有冻结基数的 CHECK 约束、并发解除幂等。
 * 使用真实线程与 CyclicBarrier 协调并发，断言最终数据；不靠休眠或打印代替验证。
 */
@SpringBootTest
class CemsConcurrencyTest {

    private static final String CODE = "OUT-CC";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);

    @Autowired
    private CemsService service;
    @Autowired
    private CemsClock clock;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CemsRepository repo;

    @BeforeEach
    void setUp() {
        CemsTestSupport.clean(jdbc);
        clock.reset();
        clock.setFixed(CemsTestSupport.T0);
        CemsTestSupport.register(service, CODE, "50", "10");
    }

    @Test
    void concurrentSameRequestIdProducesSingleResult() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<DailyEvalResult> f1 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.evaluateDaily(new DailyEvalRequest(
                        "idem-concurrent", CODE, DAY, new BigDecimal("60")));
            });
            Future<DailyEvalResult> f2 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.evaluateDaily(new DailyEvalRequest(
                        "idem-concurrent", CODE, DAY, new BigDecimal("60")));
            });

            DailyEvalResult r1 = f1.get(20, TimeUnit.SECONDS);
            DailyEvalResult r2 = f2.get(20, TimeUnit.SECONDS);
            assertNotNull(r1.markId());
            assertEquals(r1.markId(), r2.markId(), "并发同键同参必须回放首次结果");
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM exceedance_mark WHERE outlet_code = ? AND mark_type='DAILY_AVG'",
                    Long.class, CODE));
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM monitor_reading WHERE outlet_code = ? AND period_type='DAY'",
                    Long.class, CODE));
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'idem-concurrent'",
                    Long.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentOrderInsertionAcceptsOnlyOneForSameSlot() throws Exception {
        int threads = 4;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        repo.insertOrder("RO-" + CODE + "-X" + idx, CODE,
                                CemsTestSupport.T0.plusHours(idx),
                                CemsTestSupport.T0, CemsTestSupport.T0.plusHours(2), CODE);
                        accepted.incrementAndGet();
                    } catch (DuplicateKeyException e) {
                        rejected.incrementAndGet();
                    }
                    return null;
                });
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        }
        assertEquals(1, accepted.get(), "同一排口同时刻只允许一个执行中限排令");
        assertEquals(threads - 1, rejected.get(), "其余并发下达必须被唯一约束拒绝");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM restriction_order WHERE outlet_code = ?", Long.class, CODE));
    }

    @Test
    void activeOrderWithoutFrozenQuantityIsRejectedByCheckConstraint() {
        // 数据库不变量：非待确认令必须带冻结基数，杜绝“限排生效而基数未定”。
        assertThrows(Exception.class, () -> jdbc.update(
                "INSERT INTO restriction_order(order_no, outlet_code, status, issued_at, confirm_deadline, "
                        + "active_slot, created_at, updated_at) VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?, ?)",
                "RO-BAD", CODE, java.sql.Timestamp.valueOf(CemsTestSupport.T0),
                java.sql.Timestamp.valueOf(CemsTestSupport.T0.plusHours(2)), CODE,
                java.sql.Timestamp.valueOf(CemsTestSupport.T0),
                java.sql.Timestamp.valueOf(CemsTestSupport.T0)));
    }

    @Test
    void concurrentReleaseWithSameRequestIdClosesOrderOnce() throws Exception {
        service.reportHourly(new HourlyReadingRequest(
                CODE, LocalDateTime.of(2026, 9, 26, 8, 0), new BigDecimal("11")));
        service.reportHourly(new HourlyReadingRequest(
                CODE, LocalDateTime.of(2026, 9, 26, 9, 0), new BigDecimal("12")));
        var third = service.reportHourly(new HourlyReadingRequest(
                CODE, LocalDateTime.of(2026, 9, 26, 10, 0), new BigDecimal("13")));
        service.confirmOrder(new ConfirmOrderRequest("cc-confirm", third.orderNo()));
        clock.setFixed(CemsTestSupport.T0.plusHours(3));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ReleaseResult> f1 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.release(new ReleaseRestrictionRequest(
                        "cc-release", third.orderNo(),
                        LocalDateTime.of(2026, 9, 26, 14, 0), new BigDecimal("9")));
            });
            Future<ReleaseResult> f2 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.release(new ReleaseRestrictionRequest(
                        "cc-release", third.orderNo(),
                        LocalDateTime.of(2026, 9, 26, 14, 0), new BigDecimal("9")));
            });
            assertEquals("CLOSED", f1.get(20, TimeUnit.SECONDS).status());
            assertEquals("CLOSED", f2.get(20, TimeUnit.SECONDS).status());
        } finally {
            pool.shutdownNow();
        }
        assertEquals("CLOSED", service.getOrder(third.orderNo()).status());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM outlet_event WHERE outlet_code = ? AND event_type='RELEASE'",
                Long.class, CODE));
        assertEquals("MONITORING", service.getOutlet(CODE).status());
    }
}
