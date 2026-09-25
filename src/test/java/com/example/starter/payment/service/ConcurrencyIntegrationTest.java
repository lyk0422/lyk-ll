package com.example.starter.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.payment.AbstractIntegrationTest;
import com.example.starter.payment.dto.PaymentRequest;
import com.example.starter.payment.dto.PaymentResponse;
import com.example.starter.payment.dto.SettlementResponse;
import com.example.starter.payment.repo.BatchRepository;

/**
 * 入账与封账并发竞态的 H2 真实并发测试：
 * 主线程先持有批次行锁，放行后入账与封账线程在同一行锁上竞争，
 * 断言交易只能归属唯一批次：要么计入该批次清算，要么挂账（且后续只能调入下一批次一次）。
 */
class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private SettlementService settlementService;
    @Autowired
    private BatchRepository batchRepo;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private PaymentRequest payment(String rid, String serial, String day, String hour) {
        var time = LocalDate.parse(day).atTime(Integer.parseInt(hour), 0, 0).toInstant(ZoneOffset.UTC);
        return new PaymentRequest(rid, serial, "M001", new BigDecimal("100.00"), time);
    }

    @Test
    void concurrentPostAndCloseAssignsTxnToExactlyOneBatch() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger observedOutcomes = new AtomicInteger();
        try {
            // 批次日间隔 3 天，避免某次调账目标批次与后续迭代使用的批次重合。
            for (int i = 0; i < 12; i++) {
                String day = LocalDate.of(2026, 10, 1).plusDays(i * 3L).toString();
                String next = LocalDate.of(2026, 10, 1).plusDays(i * 3L + 1).toString();
                String serial = "RACE-" + i;
                String rid = "rid-" + i;
                batchRepo.ensureBatch(day);

                var start = new java.util.concurrent.CountDownLatch(1);
                Future<PaymentResponse> postFuture = pool.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return paymentService.post(payment(rid, serial, day, "10"));
                });
                Future<SettlementResponse> closeFuture = pool.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return settlementService.closeBatch(day);
                });

                // 主线程持锁期间放行两个线程，使其同时阻塞在同一批次行锁上，再提交释放锁产生真实竞争。
                transactionTemplate.executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT status FROM payment_batch WHERE batch_date = ? FOR UPDATE",
                            String.class, day);
                    start.countDown();
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });

                PaymentResponse postResp = postFuture.get(30, TimeUnit.SECONDS);
                SettlementResponse closeResp = closeFuture.get(30, TimeUnit.SECONDS);

                // 批次必为终态，且封账只发生一次。
                assertThat(closeResp.status()).isEqualTo("CLOSED");
                Integer batchCount = jdbc.queryForObject(
                        "SELECT COUNT(1) FROM payment_batch WHERE batch_date = ? AND status = 'CLOSED'",
                        Integer.class, day);
                assertThat(batchCount).isEqualTo(1);

                // 交易唯一一行，归属与计额严格一致：要么计入本批次清算一次，要么挂账（计额为 0）。
                Integer txnRows = jdbc.queryForObject(
                        "SELECT COUNT(1) FROM payment_transaction WHERE channel_serial_no = ?",
                        Integer.class, serial);
                assertThat(txnRows).isEqualTo(1);

                int settledCount = jdbc.queryForObject(
                        "SELECT COALESCE(SUM(txn_count),0) FROM settlement_order WHERE batch_date = ?",
                        Integer.class, day);

                if ("POSTED".equals(postResp.status())) {
                    assertThat(postResp.batchDate()).isEqualTo(day);
                    assertThat(settledCount).isEqualTo(1);
                } else {
                    assertThat(postResp.status()).isEqualTo("SUSPENDED");
                    assertThat(postResp.batchDate()).isEqualTo(day);
                    assertThat(settledCount).isZero();

                    // 挂账交易调账后只计入下一开放批次一次，不会两边同时计额。
                    var adjusted = paymentService.adjust(
                            new com.example.starter.payment.dto.AdjustRequest(serial, "op", null));
                    assertThat(adjusted.toBatchDate()).isEqualTo(next);
                    var nextSettlement = settlementService.closeBatch(next);
                    int nextCount = nextSettlement.items().stream()
                            .filter(item -> item.merchantNo().equals("M001"))
                            .mapToInt(com.example.starter.payment.dto.SettlementItem::txnCount).sum();
                    assertThat(nextCount).isEqualTo(1);
                    Integer stillSuspended = jdbc.queryForObject(
                            "SELECT COUNT(1) FROM payment_transaction WHERE channel_serial_no = ? "
                                    + "AND status = 'SUSPENDED'", Integer.class, serial);
                    assertThat(stillSuspended).isZero();
                }
                observedOutcomes.incrementAndGet();
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        // 无论竞争顺序如何，每轮都必须满足唯一归属不变量。
        assertThat(observedOutcomes.get()).isEqualTo(12);
    }

    @Test
    void concurrentDoubleCloseProducesSingleSettlement() throws Exception {
        String day = "2026-11-05";
        paymentService.post(payment("r1", "S1", day, "09"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        try {
            Future<SettlementResponse> f1 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return settlementService.closeBatch(day);
            });
            Future<SettlementResponse> f2 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return settlementService.closeBatch(day);
            });
            SettlementResponse r1 = f1.get(30, TimeUnit.SECONDS);
            SettlementResponse r2 = f2.get(30, TimeUnit.SECONDS);

            assertThat(r1.items()).usingRecursiveFieldByFieldElementComparator()
                    .isEqualTo(r2.items());
            // 恰好一个线程执行封账，另一个返回首次清算单（与获取锁的先后无关）。
            assertThat(r1.replayed()).isNotEqualTo(r2.replayed());
            Integer rows = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM settlement_order WHERE batch_date = ?", Integer.class, day);
            assertThat(rows).isEqualTo(1);
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentDuplicateSameRequestPostsExactlyOnce() throws Exception {
        String day = "2026-11-06";
        var request = payment("dup-rid", "DUP-S1", day, "09");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        try {
            Future<PaymentResponse> f1 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return paymentService.post(request);
            });
            Future<PaymentResponse> f2 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return paymentService.post(request);
            });
            PaymentResponse r1 = f1.get(30, TimeUnit.SECONDS);
            PaymentResponse r2 = f2.get(30, TimeUnit.SECONDS);

            assertThat(r1.status()).isEqualTo(r2.status());
            assertThat(r1.batchDate()).isEqualTo(r2.batchDate());
            Integer rows = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM payment_transaction WHERE request_id = 'dup-rid'", Integer.class);
            assertThat(rows).isEqualTo(1);
            assertThat(r1.replayed() || r2.replayed()).isTrue();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
