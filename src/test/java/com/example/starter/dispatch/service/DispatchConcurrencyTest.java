package com.example.starter.dispatch.service;

import com.example.starter.dispatch.MutableDispatchClock;
import com.example.starter.dispatch.TestClockConfiguration;
import com.example.starter.dispatch.api.DispatchException;
import com.example.starter.dispatch.api.IssueOrderRequest;
import com.example.starter.dispatch.api.OrderView;
import com.example.starter.dispatch.api.ReceiptRequest;
import com.example.starter.dispatch.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调度令 H2 真实并发测试：同闸门并发下达唯一生效、超时/最后回执竞态唯一终态、
 * 撤销/签收竞态唯一结果。使用真实线程与行锁协调，不用休眠代替断言。
 */
@SpringBootTest(classes = {
        com.example.starter.StarterApplication.class,
        TestClockConfiguration.class})
class DispatchConcurrencyTest {

    private static final Instant T0 = TestClockConfiguration.BASE_TIME;

    @Autowired
    private DispatchService service;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DispatchClock dispatchClock;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM active_gate_lock");
        jdbcTemplate.update("DELETE FROM dispatch_notification");
        jdbcTemplate.update("DELETE FROM gate_step");
        jdbcTemplate.update("DELETE FROM dispatch_order");
    }

    @Test
    void concurrentIssuesForSameGateOnlyOneSucceeds() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger busy = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String requestId = String.format("REQ-CONC-%02d", i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        IssueOrderRequest request = new IssueOrderRequest(requestId, List.of("G1"),
                                List.of(new BigDecimal("50.000")), T0.plusSeconds(3600));
                        service.issue(request);
                        success.incrementAndGet();
                    } catch (DispatchException e) {
                        if ("GATE_BUSY".equals(e.code())) {
                            busy.incrementAndGet();
                        } else {
                            other.incrementAndGet();
                        }
                    } catch (Exception e) {
                        other.incrementAndGet();
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(success.get() + busy.get()).isEqualTo(threads);
        assertThat(other.get()).isZero();
        // 恰好一张令持锁
        Integer locks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM active_gate_lock WHERE gate_code = 'G1'", Integer.class);
        assertThat(locks).isEqualTo(1);
        Integer activeOrders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dispatch_order WHERE status = 'ISSUED'", Integer.class);
        assertThat(activeOrders).isEqualTo(1);
    }

    @Test
    void timeoutAndFinalReceiptRaceProducesSingleTerminalState() throws Exception {
        // 连续两轮真实竞态：超时扫描与最后一门回执同时放行，终态只能二选一且不翻转。
        runTimeoutReceiptRace("REQ-RACE-1");
        runTimeoutReceiptRace("REQ-RACE-2");
    }

    private void runTimeoutReceiptRace(String requestId) throws Exception {
        clean();
        Instant deadline = T0.plusSeconds(600);
        service.issue(new IssueOrderRequest(requestId, List.of("G1", "G3"),
                List.of(new BigDecimal("50.000"), new BigDecimal("40.000")), deadline));
        service.acknowledge(requestId);
        service.receipt(requestId, new ReceiptRequest("G1", new BigDecimal("50.000"),
                T0.plusSeconds(60)));
        // 时钟推进到时限之后：此刻最后回执与超时扫描都具备资格，同时放行由行锁定先后。
        ((MutableDispatchClock) dispatchClock).setTime(T0.plusSeconds(601));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> receiptFuture = pool.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    service.receipt(requestId, new ReceiptRequest("G3", new BigDecimal("40.000"),
                            T0.plusSeconds(601)));
                } catch (DispatchException e) {
                    // 若超时先到，回执被拒属预期，由终态断言保证不被覆盖
                }
            });
            Future<?> sweepFuture = pool.submit(() -> {
                ready.countDown();
                await(start);
                service.sweepTimeouts();
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            receiptFuture.get(30, TimeUnit.SECONDS);
            sweepFuture.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        OrderView finalView = service.getOrder(requestId);
        assertThat(finalView.status())
                .as("超时与最后回执竞态后必须是 COMPLETED 或 TIMEOUT_ESCALATED 之一")
                .isIn(OrderStatus.COMPLETED, OrderStatus.TIMEOUT_ESCALATED);
        // 不得既超时又完成：escalated 与 COMPLETED 互斥
        if (finalView.status() == OrderStatus.COMPLETED) {
            assertThat(finalView.escalated()).isFalse();
            assertThat(finalView.gates()).extracting(g -> g.status().name())
                    .containsExactly("RECEIVED", "RECEIVED");
        } else {
            assertThat(finalView.escalated()).isTrue();
        }
        // 终态稳定：再次扫描不翻转、不重复升级
        assertThat(service.sweepTimeouts()).isZero();
        assertThat(service.getOrder(requestId).status()).isEqualTo(finalView.status());
    }

    @Test
    void cancelAndAcknowledgeRaceProducesSingleResult() throws Exception {
        // 多张令逐张发起撤销/签收并发，每张结果只能二选一，且重复操作不翻转。
        int pairs = 8;
        for (int i = 0; i < pairs; i++) {
            String requestId = String.format("REQ-RACE-CA-%02d", i);
            // 每张令复用 G1；上一张无论撤销还是签收，均在本轮处理后释放占用。
            service.issue(new IssueOrderRequest(requestId, List.of("G1"),
                    List.of(new BigDecimal("50.000")), T0.plusSeconds(3600)));
            assertSingleCancelAckOutcome(requestId);
            // 若签收方胜出，令仍占用 G1，中止以释放，供下一轮复用。
            if (service.getOrder(requestId).status() == OrderStatus.ACKNOWLEDGED) {
                service.abort(requestId, "竞态测试清理");
            }
        }
    }

    private void assertSingleCancelAckOutcome(String requestId) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger cancels = new AtomicInteger();
        AtomicInteger acks = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    service.cancel(requestId);
                    cancels.incrementAndGet();
                } catch (DispatchException ignored) {
                    // 对方先签收
                }
            }));
            futures.add(pool.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    service.acknowledge(requestId);
                    acks.incrementAndGet();
                } catch (DispatchException ignored) {
                    // 对方先撤销
                }
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(cancels.get() + acks.get()).isEqualTo(1);
        OrderView view = service.getOrder(requestId);
        assertThat(view.status())
                .isIn(OrderStatus.CANCELLED, OrderStatus.ACKNOWLEDGED);
        // 重复操作不翻转：已撤销不可签收，已签收不可撤销
        if (view.status() == OrderStatus.CANCELLED) {
            assertConflictCode(() -> service.acknowledge(requestId), "ORDER_CANCELLED");
        } else {
            assertConflictCode(() -> service.cancel(requestId), "ALREADY_ACKNOWLEDGED");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void assertConflictCode(Runnable action, String code) {
        try {
            action.run();
            throw new AssertionError("期望冲突码 " + code);
        } catch (DispatchException e) {
            assertThat(e.code()).isEqualTo(code);
        }
    }
}
