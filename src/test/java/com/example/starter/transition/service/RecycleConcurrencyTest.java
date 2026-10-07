package com.example.starter.transition.service;

import com.example.starter.transition.AbstractIntegrationTest;
import com.example.starter.transition.ApiException;
import com.example.starter.transition.dto.ApplyOrderRequest;
import com.example.starter.transition.dto.DisposeRollsRequest;
import com.example.starter.transition.dto.ExecuteOrderRequest;
import com.example.starter.transition.dto.FeedRequest;
import com.example.starter.transition.dto.InspectionRequest;
import com.example.starter.transition.dto.OrderView;
import com.example.starter.transition.dto.RecycleQuotaView;
import com.example.starter.transition.dto.ReduceRecycleQuotaRequest;
import com.example.starter.transition.dto.ReexamRequest;
import com.example.starter.transition.dto.RegisterRecycleQuotaRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
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
 * 回收投料真实并发 H2 测试：两订单不得并发吃掉同一份额度、
 * 同卷投料与改判只形成一个一致次序、同版本并发调减唯一成功。
 */
@Import(AbstractIntegrationTest.FixedClockConfiguration.class)
class RecycleConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private TransitionService transitionService;
    @Autowired
    private RecycleService recycleService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        cleanBusinessTables(jdbc);
    }

    @Test
    @DisplayName("两个订单并发投料同一卷：仅一单扣额成功，另一单409回滚，累计消费不超额度")
    void concurrentFeedsCannotConsumeSameQuotaTwice() throws Exception {
        prepareRollWithQuota("c-roll-1", "PM-RC1", "rc1", "10.000");
        String orderA = prepareExecutingOrder("PM-RC1A", "rc1a", "20.000");
        String orderB = prepareExecutingOrder("PM-RC1B", "rc1b", "20.000");
        // 两单均需 50kg×20%=10kg，恰好各自吃光同一卷的 10kg 额度。
        List<String> orders = List.of(orderA, orderB);

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        runConcurrently(2, i -> {
            barrier.await(10, TimeUnit.SECONDS);
            recycleService.submitFeed(new FeedRequest("rc1-feed-" + i, orders.get(i), 1,
                    new BigDecimal("50.000"), List.of(
                    new FeedRequest.FeedItem("c-roll-1", new BigDecimal("10.000")))));
            success.incrementAndGet();
        }, ex -> {
            if (ex instanceof ApiException api && api.getStatus() == 409) {
                conflict.incrementAndGet();
            } else {
                throw new AssertionError("非预期异常", ex);
            }
        });

        assertEquals(1, success.get(), "同一份额度只能被一个订单吃掉");
        assertEquals(1, conflict.get());
        RecycleQuotaView quota = recycleService.getQuota("c-roll-1");
        assertEquals(0, quota.consumedKg().compareTo(new BigDecimal("10.000")));
        assertEquals(0, quota.availableKg().compareTo(BigDecimal.ZERO));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(1) FROM feed_application", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(1) FROM feed_consumption", Long.class));
    }

    @Test
    @DisplayName("同卷投料与改判并发：只形成消费先成或改判先成的一个一致次序")
    void concurrentFeedAndReexamFormSingleConsistentOrder() throws Exception {
        prepareRollWithQuota("c-roll-2", "PM-RC2", "rc2", "10.000");
        // 首次检验达门槛，具备复议资格。
        transitionService.submitInspection(new InspectionRequest(
                "rc2-insp", "c-roll-2", new BigDecimal("18.000"), new BigDecimal("4.000"),
                new BigDecimal("45.000"), new BigDecimal("77.00")));
        String order = prepareExecutingOrder("PM-RC2F", "rc2f", "20.000");

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger feedSuccess = new AtomicInteger();
        AtomicInteger feedConflict = new AtomicInteger();
        AtomicInteger reexamSuccess = new AtomicInteger();
        runConcurrently(2, i -> {
            barrier.await(10, TimeUnit.SECONDS);
            if (i == 0) {
                recycleService.submitFeed(new FeedRequest("rc2-feed", order, 1,
                        new BigDecimal("50.000"), List.of(
                        new FeedRequest.FeedItem("c-roll-2", new BigDecimal("10.000")))));
                feedSuccess.incrementAndGet();
            } else {
                transitionService.submitReexam(new ReexamRequest(
                        "rc2-rex", "c-roll-2", new BigDecimal("17.000"), new BigDecimal("6.000"),
                        new BigDecimal("47.000"), new BigDecimal("77.00")));
                reexamSuccess.incrementAndGet();
            }
        }, ex -> {
            if (ex instanceof ApiException api && api.getStatus() == 409) {
                feedConflict.incrementAndGet();
            } else {
                throw new AssertionError("非预期异常", ex);
            }
        });

        assertEquals(1, reexamSuccess.get(), "改判必须成功");
        assertEquals(1, feedSuccess.get() + feedConflict.get(), "投料必居其一：先成或失败");
        assertEquals("UPGRADED", transitionService.getRoll("c-roll-2").verdict());

        RecycleQuotaView quota = recycleService.getQuota("c-roll-2");
        assertEquals("INVALIDATED", quota.status());
        assertEquals("REEXAM_UPGRADED", quota.invalidatedReason());
        assertEquals(0, quota.currentKg().compareTo(quota.consumedKg()),
                "失效后当前额度恒等于累计消费");
        if (feedSuccess.get() == 1) {
            // 消费先成：10kg 已扣，失效只清未消费部分（本例未消费为 0）。
            assertEquals(0, quota.consumedKg().compareTo(new BigDecimal("10.000")));
            assertEquals(1, quota.consumptions().size(), "已消费凭据保留");
        } else {
            // 改判先成：投料 409 回滚，无消费凭据。
            assertEquals(0, quota.consumedKg().compareTo(BigDecimal.ZERO));
            assertTrue(quota.consumptions().isEmpty());
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(1) FROM feed_application", Long.class));
        }
    }

    @Test
    @DisplayName("并发同版本调减：仅一个成功，版本只加一次")
    void concurrentReduceWithSameVersionOnlyOneSucceeds() throws Exception {
        prepareRollWithQuota("c-roll-3", "PM-RC3", "rc3", "20.000");

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        runConcurrently(2, i -> {
            barrier.await(10, TimeUnit.SECONDS);
            recycleService.reduceQuota("c-roll-3", new ReduceRecycleQuotaRequest(
                    "rc3-red-" + i, 1, new BigDecimal(i == 0 ? "15.000" : "10.000")));
            success.incrementAndGet();
        }, ex -> {
            if (ex instanceof ApiException api && api.getStatus() == 409) {
                conflict.incrementAndGet();
            } else {
                throw new AssertionError("非预期异常", ex);
            }
        });

        assertEquals(1, success.get());
        assertEquals(1, conflict.get());
        RecycleQuotaView quota = recycleService.getQuota("c-roll-3");
        assertEquals(2, quota.version(), "版本只成功加 1");
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    private void prepareRollWithQuota(String rollNo, String machine, String prefix, String kg) {
        OrderView applied = transitionService.apply(new ApplyOrderRequest(
                prefix + "-apply", machine, "G-A4-70", "G-A4-77")).data();
        transitionService.decide(applied.orderNo());
        transitionService.execute(applied.orderNo(), new ExecuteOrderRequest(
                new BigDecimal("900.000"), new BigDecimal("1.000"), 60));
        transitionService.dispose(applied.orderNo(), new DisposeRollsRequest(List.of(
                new DisposeRollsRequest.RollItem(rollNo, new BigDecimal("900.000")))));
        recycleService.registerQuota(new RegisterRecycleQuotaRequest(
                prefix + "-reg", rollNo, new BigDecimal(kg)));
    }

    private String prepareExecutingOrder(String machine, String prefix, String ratioPct) {
        OrderView applied = transitionService.apply(new ApplyOrderRequest(
                prefix + "-apply", machine, "G-A4-70", "G-A4-77", new BigDecimal(ratioPct))).data();
        transitionService.execute(applied.orderNo(), new ExecuteOrderRequest(
                new BigDecimal("900.000"), new BigDecimal("1.000"), 60));
        return applied.orderNo();
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
