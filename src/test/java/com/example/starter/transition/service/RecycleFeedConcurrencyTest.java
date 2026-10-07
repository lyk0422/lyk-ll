package com.example.starter.transition.service;

import com.example.starter.transition.AbstractIntegrationTest;
import com.example.starter.transition.ApiException;
import com.example.starter.transition.dto.AdjustRecycleQuotaRequest;
import com.example.starter.transition.dto.ApplyOrderRequest;
import com.example.starter.transition.dto.DisposeRollsRequest;
import com.example.starter.transition.dto.ExecuteOrderRequest;
import com.example.starter.transition.dto.IdempotentResult;
import com.example.starter.transition.dto.InspectionRequest;
import com.example.starter.transition.dto.OrderView;
import com.example.starter.transition.dto.RecycleQuotaView;
import com.example.starter.transition.dto.ReexamRequest;
import com.example.starter.transition.dto.RegisterRecycleQuotaRequest;
import com.example.starter.transition.dto.SubmitFeedRequest;
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
 * 回收额度与投料的真实并发 H2 测试：两个订单不能并发吃掉同一份额度、
 * 同卷投料与改判只形成一个一致次序、额度调减版本乐观并发、同键并发重放。
 */
@Import(AbstractIntegrationTest.FixedClockConfiguration.class)
class RecycleFeedConcurrencyTest extends AbstractIntegrationTest {

    private static final int THREADS = 5;

    @Autowired
    private TransitionService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        cleanBusinessTables(jdbc);
    }

    @Test
    @DisplayName("两个订单并发吃同一份额度：仅一单成功，累计消费不超过登记额度")
    void concurrentFeedsSameRollQuota() throws Exception {
        prepareDisposedRolls("cc-dis-1", "PM-CC1", "R-K1");
        service.registerRecycleQuota(new RegisterRecycleQuotaRequest("cc-rq", "R-K1", new BigDecimal("10")));
        String orderA = prepareExecutedOrder("cc-order-a", "PM-CC2");
        String orderB = prepareExecutedOrder("cc-order-b", "PM-CC3");

        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        runConcurrently(THREADS, i -> {
            barrier.await(10, TimeUnit.SECONDS);
            String orderNo = i % 2 == 0 ? orderA : orderB;
            // 总浆 50 kg × 20% = 10 kg，恰为全部额度
            service.submitFeed(new SubmitFeedRequest("cc-feed-" + i, orderNo, 1,
                    new BigDecimal("50"), List.of(new SubmitFeedRequest.FeedItem(
                    "R-K1", new BigDecimal("10")))));
            success.incrementAndGet();
        }, ex -> {
            if (ex instanceof ApiException api && api.getStatus() == 409) {
                conflict.incrementAndGet();
            } else {
                throw new AssertionError("非预期异常", ex);
            }
        });

        assertEquals(1, success.get(), "同一份额度只能被一个订单吃掉");
        assertEquals(THREADS - 1, conflict.get());
        RecycleQuotaView view = service.getRecycleQuota("R-K1");
        assertEquals(0, new BigDecimal("10").compareTo(view.consumedKg()));
        assertEquals(0, BigDecimal.ZERO.compareTo(view.availableKg()));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(1) FROM feed_batch", Long.class));
    }

    @Test
    @DisplayName("同卷投料与改判并发：只形成消费先成或改判先成的一个一致次序")
    void concurrentFeedVsUpgradeConsistentOrder() throws Exception {
        prepareDisposedRolls("cc-dis-2", "PM-CC4", "R-V1");
        service.registerRecycleQuota(new RegisterRecycleQuotaRequest("cc-rq-2", "R-V1", new BigDecimal("10")));
        // 首次检验达门槛，具备复议资格
        service.submitInspection(new InspectionRequest("cc-insp", "R-V1",
                new BigDecimal("18.000"), new BigDecimal("4.000"),
                new BigDecimal("45.000"), new BigDecimal("77.00")));
        String feedOrder = prepareExecutedOrder("cc-order-c", "PM-CC5");

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger feedSuccess = new AtomicInteger();
        AtomicInteger feedConflict = new AtomicInteger();
        // 线程 1：投料消费 4 kg（总浆 20 × 20%）
        Future<?> feed = runAsync(() -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
                service.submitFeed(new SubmitFeedRequest("cc-feed-race", feedOrder, 1,
                        new BigDecimal("20"), List.of(new SubmitFeedRequest.FeedItem(
                        "R-V1", new BigDecimal("4")))));
                feedSuccess.incrementAndGet();
            } catch (ApiException api) {
                assertEquals(409, api.getStatus(), "改判先成时投料应被拒: " + api.getMessage());
                feedConflict.incrementAndGet();
            }
        });
        // 线程 2：复议改判正品
        Future<?> upgrade = runAsync(() -> {
            barrier.await(10, TimeUnit.SECONDS);
            service.submitReexam(new ReexamRequest("cc-rex-race", "R-V1",
                    new BigDecimal("18.000"), new BigDecimal("4.000"),
                    new BigDecimal("45.000"), new BigDecimal("77.00")));
        });
        feed.get(30, TimeUnit.SECONDS);
        upgrade.get(30, TimeUnit.SECONDS);

        assertEquals("UPGRADED", service.getRoll("R-V1").verdict(), "改判必须成功");
        RecycleQuotaView view = service.getRecycleQuota("R-V1");
        assertEquals("INVALIDATED", view.status());
        assertEquals("REEXAM_UPGRADED", view.invalidReason());
        // 一致次序不变式：消费 + 失效 = 登记额度
        assertEquals(0, new BigDecimal("10").compareTo(
                view.consumedKg().add(view.invalidatedKg())),
                "消费与失效之和须等于登记额度");
        if (feedSuccess.get() == 1) {
            // 消费先成：消费 4 kg 保留，剩余 6 kg 失效
            assertEquals(0, feedConflict.get());
            assertEquals(0, new BigDecimal("4").compareTo(view.consumedKg()));
            assertEquals(0, new BigDecimal("6").compareTo(view.invalidatedKg()));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(1) FROM feed_batch", Long.class));
        } else {
            // 改判先成：投料被拒，全部 10 kg 失效
            assertEquals(1, feedConflict.get());
            assertEquals(0, BigDecimal.ZERO.compareTo(view.consumedKg()));
            assertEquals(0, new BigDecimal("10").compareTo(view.invalidatedKg()));
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(1) FROM feed_batch", Long.class));
        }
    }

    @Test
    @DisplayName("并发调减同一额度（同 expectedVersion）：唯一成功，版本只 +1 一次")
    void concurrentAdjustSameExpectedVersion() throws Exception {
        prepareDisposedRolls("cc-dis-3", "PM-CC6", "R-A9");
        service.registerRecycleQuota(new RegisterRecycleQuotaRequest("cc-rq-3", "R-A9", new BigDecimal("10")));

        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        runConcurrently(THREADS, i -> {
            barrier.await(10, TimeUnit.SECONDS);
            service.adjustRecycleQuota("R-A9",
                    new AdjustRecycleQuotaRequest("cc-adj-" + i, 1, new BigDecimal("8")));
            success.incrementAndGet();
        }, ex -> {
            if (ex instanceof ApiException api && api.getStatus() == 409) {
                conflict.incrementAndGet();
            } else {
                throw new AssertionError("非预期异常", ex);
            }
        });

        assertEquals(1, success.get(), "同版本号并发调减只允许一次成功");
        assertEquals(THREADS - 1, conflict.get());
        RecycleQuotaView view = service.getRecycleQuota("R-A9");
        assertEquals(2, view.version());
        assertEquals(0, new BigDecimal("8").compareTo(view.registeredKg()));
    }

    @Test
    @DisplayName("同键同参并发登记：唯一受理，其余重放首次结果，额度只登记一次")
    void concurrentRegisterSameRequestId() throws Exception {
        prepareDisposedRolls("cc-dis-4", "PM-CC7", "R-R9");

        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        runConcurrently(THREADS, i -> {
            barrier.await(10, TimeUnit.SECONDS);
            IdempotentResult<RecycleQuotaView> result = service.registerRecycleQuota(
                    new RegisterRecycleQuotaRequest("cc-same-key", "R-R9", new BigDecimal("10")));
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
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM recycle_quota WHERE roll_no = 'R-R9'", Long.class));
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    /** 申请（携带比例 20%，同事务判定）并执行，返回 EXECUTED 状态单号。 */
    private String prepareExecutedOrder(String requestId, String machine) {
        OrderView applied = service.apply(new ApplyOrderRequest(
                requestId, machine, "G-A4-70", "G-A4-77", new BigDecimal("20.000"))).data();
        return service.execute(applied.orderNo(), new ExecuteOrderRequest(
                new BigDecimal("900.000"), new BigDecimal("1.000"), 60)).orderNo();
    }

    /** 申请→判定→执行→处置，登记过渡卷（强制降级）。 */
    private void prepareDisposedRolls(String requestId, String machine, String... rollNos) {
        OrderView applied = service.apply(new ApplyOrderRequest(
                requestId, machine, "G-A4-70", "G-A4-77")).data();
        service.decide(applied.orderNo());
        service.execute(applied.orderNo(), new ExecuteOrderRequest(
                new BigDecimal("900.000"), new BigDecimal("1.000"), 60));
        List<DisposeRollsRequest.RollItem> items = java.util.Arrays.stream(rollNos)
                .map(r -> new DisposeRollsRequest.RollItem(r, new BigDecimal("100.5")))
                .toList();
        service.dispose(applied.orderNo(), new DisposeRollsRequest(items));
    }

    private Future<?> runAsync(ThrowingRunnable action) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            return pool.submit(() -> {
                try {
                    action.run();
                } catch (AssertionError e) {
                    throw e;
                } catch (Exception e) {
                    throw new AssertionError("并发任务失败", e);
                } finally {
                    pool.shutdown();
                }
            });
        } catch (RuntimeException e) {
            pool.shutdownNow();
            throw e;
        }
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

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
