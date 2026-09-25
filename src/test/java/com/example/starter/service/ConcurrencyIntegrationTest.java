package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.dto.AdjustRequest;
import com.example.starter.dto.CloseBatchRequest;
import com.example.starter.dto.PaymentRequest;
import com.example.starter.dto.ReconRequest;
import com.example.starter.dto.ReconRequest.ReconEntry;
import com.example.starter.dto.ReconResponse;
import com.example.starter.dto.ResolveRequest;
import com.example.starter.entity.ReconDiff;
import com.example.starter.exception.ConflictException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 真实多线程下的入账/封账竞态、流水唯一、核销唯一与调账唯一测试（H2 行锁）。 */
class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate BATCH = LocalDate.of(2026, 9, 20);
    private static final Instant IN_BATCH = Instant.parse("2026-09-20T10:00:00Z");

    @org.springframework.beans.factory.annotation.Autowired
    private PaymentService paymentService;

    @Test
    void concurrentPostAndCloseAssignsTxnToExactlyOneBatchState() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch ready = new CountDownLatch(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        Map<Integer, Object> outcomes = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    try {
                        outcomes.put(idx, paymentService.postPayment(
                                new PaymentRequest("RACE-REQ-" + idx, "RACE-FLOW-" + idx,
                                        "M001", 10_000L, IN_BATCH.plusSeconds(idx))));
                    } catch (RuntimeException ex) {
                        outcomes.put(idx, ex);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        futures.add(pool.submit(() -> {
            ready.countDown();
            try {
                start.await();
                try {
                    outcomes.put(-1, paymentService.closeBatch(new CloseBatchRequest(BATCH)));
                } catch (RuntimeException ex) {
                    outcomes.put(-1, ex);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }));

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // 无异常；所有交易要么 POSTED 计入清算，要么 SUSPENDED 挂账，不丢不重。
        long posted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_txn WHERE status = 'POSTED'", Long.class);
        long suspended = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_txn WHERE status = 'SUSPENDED'", Long.class);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_txn", Long.class);
        assertThat(total).isEqualTo((long) threads);
        assertThat(posted + suspended).isEqualTo((long) threads);

        // 封账清算总额与库中 POSTED 金额严格一致，不会把挂账计进来。
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, total_amount FROM settlement_batch WHERE batch_date = ?", BATCH);
        assertThat(row.get("status")).isEqualTo("CLOSED");
        Long postedAmount = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM payment_txn WHERE status = 'POSTED'", Long.class);
        assertThat(((Number) row.get("total_amount")).longValue()).isEqualTo(postedAmount);

        // 封账幂等：重复封账返回首次结果且不重算。
        assertThat(paymentService.closeBatch(new CloseBatchRequest(BATCH)).totalAmount())
                .isEqualTo(postedAmount);
    }

    @Test
    void concurrentSameFlowOnlyOneSucceeds() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                return paymentService.postPayment(new PaymentRequest(
                        "DUP-REQ-" + idx, "DUP-FLOW", "M001", 10_000L, IN_BATCH));
            }));
        }
        start.countDown();

        int success = 0;
        int conflict = 0;
        for (Future<?> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
                success++;
            } catch (Exception ex) {
                assertThat(ex.getCause()).isInstanceOf(ConflictException.class);
                conflict++;
            }
        }
        pool.shutdown();
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_txn WHERE channel_flow_no = 'DUP-FLOW' AND batch_date = ?",
                Long.class, BATCH);
        assertThat(rows).isEqualTo(1L);
    }

    @Test
    void concurrentSameRequestIdSameParamsIsIdempotent() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<com.example.starter.dto.PaymentResponse>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return paymentService.postPayment(new PaymentRequest(
                        "SAME-REQ", "SAME-FLOW", "M001", 10_000L, IN_BATCH));
            }));
        }
        start.countDown();

        List<com.example.starter.dto.PaymentResponse> responses = new ArrayList<>();
        for (Future<com.example.starter.dto.PaymentResponse> future : futures) {
            responses.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(responses).hasSize(threads);
        assertThat(responses).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo("POSTED");
            assertThat(r.batchDate()).isEqualTo(BATCH);
        });
        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_txn WHERE request_id = 'SAME-REQ'", Long.class);
        assertThat(rows).isEqualTo(1L);
    }

    @Test
    void concurrentResolveOnlyOneWins() throws Exception {
        paymentService.postPayment(
                new PaymentRequest("RC-REQ", "RC-FLOW", "M001", 10_000L, IN_BATCH));
        ReconResponse recon = paymentService.reconcile(
                new ReconRequest("RC-FILE", BATCH, null, List.of()));
        Long diffId = recon.diffs().get(0).getId();

        int threads = 3;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReconDiff>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return paymentService.resolveDiff(
                        diffId, new ResolveRequest("WRITE_OFF", "worker", null));
            }));
        }
        start.countDown();

        int success = 0;
        int conflict = 0;
        for (Future<ReconDiff> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
                success++;
            } catch (Exception ex) {
                assertThat(ex.getCause()).isInstanceOf(ConflictException.class);
                conflict++;
            }
        }
        pool.shutdown();
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, resolution FROM recon_diff WHERE id = ?", diffId);
        assertThat(row.get("status")).isEqualTo("RESOLVED");
        assertThat(row.get("resolution")).isEqualTo("WRITE_OFF");
    }

    @Test
    void concurrentAdjustOnlyOneMovesSuspendedTxn() throws Exception {
        paymentService.postPayment(
                new PaymentRequest("CA-REQ-A", "CA-FLOW-A", "M001", 10_000L, IN_BATCH));
        paymentService.closeBatch(new CloseBatchRequest(BATCH));
        paymentService.postPayment(
                new PaymentRequest("CA-REQ-B", "CA-FLOW-B", "M001", 30_000L, IN_BATCH.plusSeconds(9)));

        LocalDate nextBatch = BATCH.plusDays(1);
        int threads = 3;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                return paymentService.adjust(
                        new AdjustRequest("CA-ADJ-" + idx, "CA-FLOW-B", BATCH, nextBatch));
            }));
        }
        start.countDown();

        int success = 0;
        int conflict = 0;
        for (Future<?> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
                success++;
            } catch (Exception ex) {
                assertThat(ex.getCause()).isInstanceOf(ConflictException.class);
                conflict++;
            }
        }
        pool.shutdown();
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);

        // 挂账只被调入一次：交易 MOVED、目标批次清算恰好 30000，无双边计额。
        Map<String, Object> txnRow = jdbcTemplate.queryForMap(
                "SELECT status, batch_date, origin_batch_date FROM payment_txn "
                        + "WHERE channel_flow_no = 'CA-FLOW-B'");
        assertThat(txnRow.get("status")).isEqualTo("MOVED");
        Long adjustments = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM adjustment WHERE txn_id = "
                        + "(SELECT id FROM payment_txn WHERE channel_flow_no = 'CA-FLOW-B')", Long.class);
        assertThat(adjustments).isEqualTo(1L);
        assertThat(paymentService.getBatch(BATCH).totalAmount()).isEqualTo(10_000L);
        assertThat(paymentService.closeBatch(new CloseBatchRequest(nextBatch)).totalAmount())
                .isEqualTo(30_000L);
    }
}
