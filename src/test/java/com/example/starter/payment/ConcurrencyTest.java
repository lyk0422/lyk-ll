package com.example.starter.payment;

import com.example.starter.payment.service.PaymentService;
import com.example.starter.payment.service.PaymentService.PostCommand;
import com.example.starter.payment.service.PaymentService.PostResult;
import com.example.starter.payment.service.SettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发边界：同 requestId 并发入账幂等；封账与入账竞态下交易只归属唯一批次。
 */
class ConcurrencyTest extends AbstractPaymentIntegrationTest {

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private SettlementService settlementService;

    private static final LocalDate DAY = LocalDate.of(2026, 9, 24);
    private static final Instant TXN_TIME = Instant.parse("2026-09-24T10:00:00Z");

    @Test
    void concurrentSameRequestIdPostsOnlyOnce() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PostResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return paymentService.post(new PostCommand("r-same", "S-same", "M0001", 10000, TXN_TIME));
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        List<PostResult> results = new ArrayList<>();
        for (Future<PostResult> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        // 全部请求返回同一条交易，数据库仅入账一次
        long firstId = results.get(0).txn().id();
        assertThat(results).allSatisfy(r -> assertThat(r.txn().id()).isEqualTo(firstId));
        assertThat(countRows("pay_transaction")).isEqualTo(1);
    }

    @Test
    void concurrentCloseAndPostAssignsUniqueBatch() throws Exception {
        int posters = 10;
        ExecutorService pool = Executors.newFixedThreadPool(posters + 1);
        CountDownLatch ready = new CountDownLatch(posters + 1);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<PostResult>> postFutures = new ArrayList<>();
        for (int i = 0; i < posters; i++) {
            int idx = i;
            postFutures.add(pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return paymentService.post(new PostCommand("r-" + idx, "S-" + idx, "M0001",
                        10000, TXN_TIME));
            }));
        }
        Future<List<SettlementBill>> closeFuture = pool.submit(() -> {
            ready.countDown();
            go.await(5, TimeUnit.SECONDS);
            return settlementService.close(DAY);
        });

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        List<PostResult> results = new ArrayList<>();
        for (Future<PostResult> f : postFutures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        List<SettlementBill> bills = closeFuture.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        // 每笔交易要么进入该批次（POSTED），要么挂账（SUSPENDED），不存在中间态
        assertThat(results).allSatisfy(r -> {
            if (PayTransaction.POSTED.equals(r.txn().status())) {
                assertThat(r.txn().batchId()).isNotNull();
            } else {
                assertThat(r.txn().status()).isEqualTo(PayTransaction.SUSPENDED);
                assertThat(r.txn().batchId()).isNull();
            }
        });

        // 一致性：批次内 POSTED 交易总额 == 清算单计额总额（无丢失、无重复计额）
        List<Map<String, Object>> postedRows = jdbc.queryForList(
                "SELECT amount FROM pay_transaction WHERE status = 'POSTED'");
        long postedSum = postedRows.stream().mapToLong(row -> ((Number) row.get("amount")).longValue()).sum();
        long billedSum = bills.stream().mapToLong(SettlementBill::totalAmount).sum();
        assertThat(postedSum).isEqualTo(billedSum);

        // 每笔交易归属唯一批次：全库无重复流水号
        Integer distinctSerials = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT serial_no) FROM pay_transaction", Integer.class);
        assertThat(distinctSerials).isEqualTo(posters);
    }
}
