package com.example.starter.transition.service;

import com.example.starter.transition.AbstractIntegrationTest;
import com.example.starter.transition.ApiException;
import com.example.starter.transition.dto.ApplyOrderRequest;
import com.example.starter.transition.dto.BlendAdjustRequest;
import com.example.starter.transition.dto.BlendReadingRequest;
import com.example.starter.transition.dto.BlendRatioVersionView;
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
 * 掺配比例真实并发 H2 测试：同时刻执行中调整唯一受理、同时刻读数去重、同键并发重放。
 * 全部线程通过栅栏同时发起，断言最终数据而非打印结果。
 */
@Import(AbstractIntegrationTest.FixedClockConfiguration.class)
class BlendConcurrencyTest extends AbstractIntegrationTest {

    private static final int THREADS = 6;
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 25, 10, 0, 0);

    @Autowired
    private TransitionService transitionService;
    @Autowired
    private BlendService blendService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        cleanBusinessTables(jdbc);
    }

    @Test
    @DisplayName("并发执行中调整同一订单：唯一受理，其余409，版本链无重复无空洞")
    void concurrentAdjustOnlyOneAccepted() throws Exception {
        String orderNo = prepareExecutedOrderWithRatio("cc-adj-apply", "PM-BC1", "20.000");

        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        runConcurrently(i -> {
            barrier.await(10, TimeUnit.SECONDS);
            blendService.adjustRatio(orderNo, new BlendAdjustRequest(
                    "cc-adj-" + i, new BigDecimal("22.000")));
            success.incrementAndGet();
        }, ex -> {
            if (ex instanceof ApiException api && api.getStatus() == 409) {
                conflict.incrementAndGet();
            } else {
                throw new AssertionError("非预期异常", ex);
            }
        });

        assertEquals(1, success.get(), "同一订单同时刻只允许一次执行中调整");
        assertEquals(THREADS - 1, conflict.get(), "其余并发调整全部 409");
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_ratio_version WHERE order_no = ?", Long.class, orderNo),
                "版本链仅有初始版本与一次成功调整");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_ratio_version WHERE order_no = ? AND version_no = 2",
                Long.class, orderNo), "版本 2 唯一，无重复");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_control_state WHERE order_no = ? AND current_version_no = 2",
                Long.class, orderNo), "控制状态与版本链一致");
    }

    @Test
    @DisplayName("并发同时刻读数：唯一登记，其余409，读数表仅一条")
    void concurrentReadingSameInstantDeduplicated() throws Exception {
        String orderNo = prepareExecutedOrderWithRatio("cc-rd-apply", "PM-BC2", "20.000");

        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        runConcurrently(i -> {
            barrier.await(10, TimeUnit.SECONDS);
            blendService.submitReading(orderNo, new BlendReadingRequest(
                    "cc-rd-" + i, T0, new BigDecimal("20.500")));
            success.incrementAndGet();
        }, ex -> {
            if (ex instanceof ApiException api && api.getStatus() == 409) {
                conflict.incrementAndGet();
            } else {
                throw new AssertionError("非预期异常", ex);
            }
        });

        assertEquals(1, success.get(), "同一订单同时刻只允许一条读数");
        assertEquals(THREADS - 1, conflict.get());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_reading WHERE order_no = ?", Long.class, orderNo));
    }

    @Test
    @DisplayName("同键同参并发调整：唯一受理，其余重放首次结果，版本链仅前进一次")
    void concurrentSameRequestIdAdjustYieldsSingleAcceptAndReplays() throws Exception {
        String orderNo = prepareExecutedOrderWithRatio("cc-same-apply", "PM-BC3", "20.000");

        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        runConcurrently(i -> {
            barrier.await(10, TimeUnit.SECONDS);
            IdempotentResult<BlendRatioVersionView> result = blendService.adjustRatio(orderNo,
                    new BlendAdjustRequest("cc-same-key", new BigDecimal("22.000")));
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

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    private String prepareExecutedOrderWithRatio(String requestId, String machine, String ratio) {
        OrderView applied = transitionService.apply(new ApplyOrderRequest(
                requestId, machine, "G-A4-70", "G-A4-77", new BigDecimal(ratio))).data();
        transitionService.decide(applied.orderNo());
        transitionService.execute(applied.orderNo(), new ExecuteOrderRequest(
                new BigDecimal("820.000"), new BigDecimal("0.850"), 120));
        return applied.orderNo();
    }

    private void runConcurrently(ConcurrentAction action, ErrorHandler handler) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<Void>> futures = IntStream.range(0, THREADS)
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
