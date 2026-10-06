package com.example.starter.transition.service;

import com.example.starter.transition.AbstractIntegrationTest;
import com.example.starter.transition.ApiException;
import com.example.starter.transition.TransitionStatus;
import com.example.starter.transition.dto.AdjustBlendRatioRequest;
import com.example.starter.transition.dto.ApplyOrderRequest;
import com.example.starter.transition.dto.BlendReadingRequest;
import com.example.starter.transition.dto.BlendReadingView;
import com.example.starter.transition.dto.BlendVersionView;
import com.example.starter.transition.dto.ExecuteOrderRequest;
import com.example.starter.transition.dto.IdempotentResult;
import com.example.starter.transition.dto.OrderView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 掺配比例真实并发 H2 测试：同一订单同时刻只允许一次执行中调整、
 * 读数按订单与采样时刻并发去重、同键并发重放。
 */
@Import(AbstractIntegrationTest.FixedClockConfiguration.class)
class BlendRatioConcurrencyTest extends AbstractIntegrationTest {

    private static final int THREADS = 5;
    private static final LocalDateTime SAMPLED_AT = LocalDateTime.of(2026, 9, 25, 11, 0, 0);

    @Autowired
    private TransitionService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        cleanBusinessTables(jdbc);
    }

    @Test
    @DisplayName("并发执行中调整同一订单（固定时钟同时刻）：唯一受理，其余409，版本链只增一档")
    void concurrentAdjustSameTimeOnlyOneAccepted() throws Exception {
        String orderNo = prepareExecutedOrder("cc-adj", "PM-CB1");

        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        runConcurrently(THREADS, i -> {
            barrier.await(10, TimeUnit.SECONDS);
            service.adjustBlendRatio(orderNo,
                    new AdjustBlendRatioRequest("cc-adj-" + i, new BigDecimal("20.000")));
            success.incrementAndGet();
        }, ex -> {
            if (ex instanceof ApiException api && api.getStatus() == 409) {
                conflict.incrementAndGet();
            } else {
                throw new AssertionError("非预期异常", ex);
            }
        });

        assertEquals(1, success.get(), "同时刻只允许一次执行中调整");
        assertEquals(THREADS - 1, conflict.get());
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_ratio_version WHERE order_no = ?", Long.class, orderNo),
                "APPLY v1 + ADJUST v2，并发不产生重复版本");
        assertEquals(2, service.getOrder(orderNo).currentBlendVersion());
    }

    @Test
    @DisplayName("并发提交同一订单同一采样时刻读数：唯一登记，其余409，最终仅一条读数")
    void concurrentReadingSameSampledAtDeduplicated() throws Exception {
        String orderNo = prepareExecutedOrder("cc-rd", "PM-CB2");

        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        runConcurrently(THREADS, i -> {
            barrier.await(10, TimeUnit.SECONDS);
            service.submitBlendReading(new BlendReadingRequest(
                    "cc-rd-" + i, orderNo, SAMPLED_AT, new BigDecimal("21.000")));
            success.incrementAndGet();
        }, ex -> {
            if (ex instanceof ApiException api && api.getStatus() == 409) {
                conflict.incrementAndGet();
            } else {
                throw new AssertionError("非预期异常", ex);
            }
        });

        assertEquals(1, success.get(), "同一订单同一采样时刻只登记一条读数");
        assertEquals(THREADS - 1, conflict.get());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_reading WHERE order_no = ?", Long.class, orderNo));
    }

    @Test
    @DisplayName("同键同参并发调整：唯一受理，其余重放首次结果，版本链只增一档")
    void concurrentSameRequestIdAdjustYieldsSingleAccept() throws Exception {
        String orderNo = prepareExecutedOrder("cc-key", "PM-CB3");

        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        runConcurrently(THREADS, i -> {
            barrier.await(10, TimeUnit.SECONDS);
            IdempotentResult<BlendVersionView> result = service.adjustBlendRatio(orderNo,
                    new AdjustBlendRatioRequest("cc-same-key", new BigDecimal("20.000")));
            if (result.replayed()) {
                replayed.incrementAndGet();
            } else {
                created.incrementAndGet();
            }
        }, ex -> {
            throw new AssertionError("同键同参并发不应抛异常", ex);
        });

        assertEquals(1, created.get());
        assertEquals(THREADS - 1, replayed.get());
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_ratio_version WHERE order_no = ?", Long.class, orderNo));
    }

    @Test
    @DisplayName("并发提交触发失控的第3个超差读数：暂停转人工只发生一次")
    void concurrentOutOfControlPauseHappensOnce() throws Exception {
        String orderNo = prepareExecutedOrder("cc-pause", "PM-CB4");
        // 先累计 2 个连续超差点
        service.submitBlendReading(new BlendReadingRequest(
                "cc-p1", orderNo, SAMPLED_AT, new BigDecimal("28.000")));
        service.submitBlendReading(new BlendReadingRequest(
                "cc-p2", orderNo, SAMPLED_AT.plusMinutes(1), new BigDecimal("28.000")));

        // 并发提交第 3 个超差点（不同时刻避免去重干扰）：失控暂停只登记一次
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        AtomicInteger submitted = new AtomicInteger();
        runConcurrently(THREADS, i -> {
            barrier.await(10, TimeUnit.SECONDS);
            IdempotentResult<BlendReadingView> result = service.submitBlendReading(
                    new BlendReadingRequest("cc-p3-" + i, orderNo,
                            SAMPLED_AT.plusMinutes(2 + i), new BigDecimal("28.500")));
            if (!result.replayed()) {
                submitted.incrementAndGet();
            }
        }, ex -> {
            // 先失控暂停的事务提交后，其余读数因 PAUSED 被拒 409，属预期
            if (!(ex instanceof ApiException api && api.getStatus() == 409)) {
                throw new AssertionError("非预期异常", ex);
            }
        });

        assertTrue(submitted.get() >= 1, "至少一个读数触发失控");
        assertEquals(TransitionStatus.PAUSED, service.getOrder(orderNo).status());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_control_event WHERE order_no = ? AND event_type = 'OUT_OF_CONTROL_PAUSE'",
                Long.class, orderNo), "失控暂停转人工事件唯一");
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    /** 申请携带比例（同事务判定）并执行，返回 EXECUTED 状态单号。 */
    private String prepareExecutedOrder(String requestId, String machine) {
        OrderView applied = service.apply(new ApplyOrderRequest(
                requestId, machine, "G-A4-70", "G-A4-77", new BigDecimal("25.000"))).data();
        assertEquals(TransitionStatus.DECIDED, applied.status());
        return service.execute(applied.orderNo(), new ExecuteOrderRequest(
                new BigDecimal("900.000"), new BigDecimal("1.000"), 60)).orderNo();
    }

    private void runConcurrently(int threads, ConcurrentAction action, ErrorHandler handler)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Void>> futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        try {
                            action.run(i);
                        } catch (BrokenBarrierException | InterruptedException e) {
                            throw new AssertionError("并发同步失败", e);
                        } catch (Exception e) {
                            handler.handle(e);
                        }
                        return null;
                    })
                    .map(pool::submit)
                    .toList();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @FunctionalInterface
    private interface ConcurrentAction {
        void run(int index) throws Exception;
    }

    @FunctionalInterface
    private interface ErrorHandler {
        void handle(Exception ex);
    }
}
