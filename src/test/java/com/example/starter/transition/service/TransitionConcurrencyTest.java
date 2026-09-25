package com.example.starter.transition.service;

import com.example.starter.transition.AbstractIntegrationTest;
import com.example.starter.transition.ApiException;
import com.example.starter.transition.dto.ApplyOrderRequest;
import com.example.starter.transition.dto.DisposeRollsRequest;
import com.example.starter.transition.dto.ExecuteOrderRequest;
import com.example.starter.transition.dto.IdempotentResult;
import com.example.starter.transition.dto.InspectionRequest;
import com.example.starter.transition.dto.OrderView;
import com.example.starter.transition.dto.ReexamRequest;
import com.example.starter.transition.dto.RollView;
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
 * 真实并发 H2 测试：纸机占用唯一受理、同键并发重放、复议并发唯一终态。
 * 全部线程通过栅栏同时发起，断言最终数据而非打印结果。
 */
@Import(AbstractIntegrationTest.FixedClockConfiguration.class)
class TransitionConcurrencyTest extends AbstractIntegrationTest {

    private static final int THREADS = 6;

    @Autowired
    private TransitionService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        cleanBusinessTables(jdbc);
    }

    @Test
    @DisplayName("并发申请同一纸机：唯一受理，其余409，最终仅一张执行中单据与一个占用")
    void concurrentApplyOnlyOneAccepted() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        runConcurrently(i -> {
            barrier.await(10, TimeUnit.SECONDS);
            service.apply(new ApplyOrderRequest(
                    "c-apply-" + i, "PM-C1", "G-A4-70", "G-A4-77"));
            success.incrementAndGet();
        }, ex -> {
            if (ex instanceof ApiException api && api.getStatus() == 409) {
                conflict.incrementAndGet();
            } else {
                throw new AssertionError("非预期异常", ex);
            }
        });

        assertEquals(1, success.get(), "仅一张申请受理成功");
        assertEquals(THREADS - 1, conflict.get(), "其余并发申请全部 409");
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(1) FROM transition_order", Long.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM machine_active_slot WHERE paper_machine = 'PM-C1' AND active_flag = 'ACTIVE'",
                Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(1) FROM idempotent_request", Long.class),
                "失败事务回滚后只保留受理成功的幂等记录");
    }

    @Test
    @DisplayName("同键同参并发申请：唯一受理，其余重放首次结果，不产生新单据")
    void concurrentSameRequestIdYieldsSingleAcceptAndReplays() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        runConcurrently(i -> {
            barrier.await(10, TimeUnit.SECONDS);
            IdempotentResult<OrderView> result = service.apply(new ApplyOrderRequest(
                    "c-same-key", "PM-C2", "G-A4-70", "G-A4-77"));
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
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(1) FROM transition_order", Long.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM machine_active_slot WHERE paper_machine = 'PM-C2' AND active_flag = 'ACTIVE'",
                Long.class));
    }

    @Test
    @DisplayName("并发复议：仅一个改判成功形成唯一终态，另一个409")
    void concurrentReexamProducesSingleFinalVerdict() throws Exception {
        prepareRollWithEligibleInspection("c-roll-1", "PM-C3", "c-order-1");

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger upgraded = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        runConcurrently(2, i -> {
            barrier.await(10, TimeUnit.SECONDS);
            IdempotentResult<RollView> result = service.submitReexam(new ReexamRequest(
                    "c-rex-" + i, "c-roll-1", new BigDecimal("17.000"), new BigDecimal("6.000"),
                    new BigDecimal("47.000"), new BigDecimal("77.00")));
            if ("UPGRADED".equals(result.data().verdict())) {
                upgraded.incrementAndGet();
            }
        }, ex -> {
            if (ex instanceof ApiException api && api.getStatus() == 409) {
                conflict.incrementAndGet();
            } else {
                throw new AssertionError("非预期异常", ex);
            }
        });

        assertEquals(1, upgraded.get(), "唯一改判成功");
        assertEquals(1, conflict.get(), "并发复议唯一终态");
        RollView roll = service.getRoll("c-roll-1");
        assertEquals("UPGRADED", roll.verdict());
        assertEquals("GENUINE", roll.warehouseStatus());
        assertEquals(1, roll.reexamCount());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_inspection WHERE roll_no = 'c-roll-1' AND inspection_type = 'REEXAM'",
                Long.class), "复议检验记录唯一");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_verdict_event WHERE roll_no = 'c-roll-1' AND event_type = 'REEXAM_UPGRADED'",
                Long.class), "改判链中改判事件唯一");
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    private void prepareRollWithEligibleInspection(String rollNo, String machine, String requestPrefix) {
        OrderView applied = service.apply(new ApplyOrderRequest(
                requestPrefix + "-apply", machine, "G-A4-70", "G-A4-77")).data();
        service.decide(applied.orderNo());
        service.execute(applied.orderNo(), new ExecuteOrderRequest(
                new BigDecimal("900.000"), new BigDecimal("1.000"), 60));
        service.dispose(applied.orderNo(), new DisposeRollsRequest(List.of(
                new DisposeRollsRequest.RollItem(rollNo, new BigDecimal("900.000")))));
        service.submitInspection(new InspectionRequest(
                requestPrefix + "-insp", rollNo, new BigDecimal("18.000"), new BigDecimal("4.000"),
                new BigDecimal("45.000"), new BigDecimal("77.00")));
        assertTrue(service.getRoll(rollNo).eligibleReexam());
    }

    private void runConcurrently(ConcurrentAction action, ErrorHandler handler) throws Exception {
        runConcurrently(THREADS, action, handler);
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
