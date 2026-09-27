package com.example.starter.metro;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.metro.dto.EntryRequest;
import com.example.starter.metro.dto.ExitRequest;
import com.example.starter.metro.dto.SealRequest;
import com.example.starter.metro.dto.refund.AdjustmentIncludeRequest;
import com.example.starter.metro.dto.refund.RefundApplyRequest;
import com.example.starter.metro.dto.refund.RefundConfirmRequest;
import com.example.starter.metro.dto.refund.RefundConfirmResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退款冲正并发与幂等边界测试：真实多线程并发确认/纳入，
 * 验证行锁串行、可退余额不超退、单一开放批次与幂等重放。
 */
@SpringBootTest
class RefundConcurrencyTest {

    @Autowired
    private MetroService metroService;

    @Autowired
    private RefundService refundService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM adjustment_detail");
        jdbcTemplate.update("DELETE FROM adjustment_order");
        jdbcTemplate.update("DELETE FROM adjustment_batch");
        jdbcTemplate.update("DELETE FROM refund_order");
        jdbcTemplate.update("DELETE FROM metro_card_close");
        jdbcTemplate.update("DELETE FROM clearing_detail");
        jdbcTemplate.update("DELETE FROM clearing_discrepancy");
        jdbcTemplate.update("DELETE FROM metro_transaction");
        jdbcTemplate.update("DELETE FROM clearing_batch");
        jdbcTemplate.update("DELETE FROM idempotency_record");
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }

    private void sealedTrip(String txnNo, String cardId, String from, String to, String day) {
        LocalDate date = LocalDate.parse(day);
        metroService.entry(new EntryRequest("e-" + txnNo, txnNo, cardId, from, date.atTime(9, 0)));
        metroService.exit(new ExitRequest("x-" + txnNo, txnNo, to, date.atTime(9, 30)));
        metroService.seal(new SealRequest("s-" + txnNo, date));
    }

    private void applyRefund(String requestId, String refundNo, String txnNo, long amount) {
        refundService.apply(new RefundApplyRequest(requestId, refundNo, txnNo, amount,
                LocalDateTime.of(2026, 9, 26, 10, 0)));
    }

    private RefundConfirmRequest confirmRequest(String requestId, String refundNo, int reviewerSeed) {
        return new RefundConfirmRequest(requestId, refundNo, 1,
                "U" + (1000 + reviewerSeed), LocalDateTime.of(2026, 9, 26, 10, 5),
                "U" + (2000 + reviewerSeed), LocalDateTime.of(2026, 9, 26, 10, 6));
    }

    private <T> List<T> runConcurrently(int threads, java.util.function.IntFunction<T> task)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return task.apply(index);
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(20, TimeUnit.SECONDS));
        }
        return results;
    }

    @Test
    void concurrentSameKeyConfirmSucceedsOnceAndRestReplay() throws Exception {
        sealedTrip("CT-1", "CC-1", "A", "D", "2026-09-25");
        applyRefund("ca-1", "CR-1", "CT-1", 100);
        RefundConfirmRequest request = confirmRequest("ck-1", "CR-1", 1);

        List<RefundConfirmResponse> responses = runConcurrently(8, i -> refundService.confirm(request));
        long fresh = responses.stream().filter(r -> !r.replayed()).count();
        assertEquals(1L, fresh, "同键并发确认只有一笔实际生效");
        for (RefundConfirmResponse response : responses) {
            assertEquals("ADJ-CR-1", response.adjustmentNo());
            assertEquals(2, response.version());
            assertEquals(100L, response.amountCents());
        }
        Integer refundConfirmed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_order WHERE refund_no = 'CR-1' AND status = 'CONFIRMED'",
                Integer.class);
        assertEquals(1, refundConfirmed);
        Integer adjustmentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM adjustment_order WHERE refund_no = 'CR-1'", Integer.class);
        assertEquals(1, adjustmentCount);
        Long refundedTotal = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(-amount_cents),0) FROM adjustment_detail WHERE txn_no = 'CT-1'",
                Long.class);
        assertEquals(100L, refundedTotal);
    }

    @Test
    void concurrentConfirmsOnSameTripNeverExceedRefundableBalance() throws Exception {
        // A->F 原实收 500 分（L1 188、L2 312）
        sealedTrip("CT-2", "CC-2", "A", "F", "2026-09-25");
        int refunds = 8;
        for (int i = 0; i < refunds; i++) {
            applyRefund("ca-2-" + i, "CR-2-" + i, "CT-2", 100);
        }

        List<Object> outcomes = runConcurrently(refunds, i -> {
            try {
                return refundService.confirm(confirmRequest("ck-2-" + i, "CR-2-" + i, i));
            } catch (MetroException ex) {
                return ex;
            }
        });

        long successes = outcomes.stream().filter(o -> o instanceof RefundConfirmResponse).count();
        assertEquals(5L, successes, "500 分可退余额、每笔 100 分，恰允许 5 笔生效");
        long rejected = outcomes.stream()
                .filter(o -> o instanceof MetroException)
                .map(o -> (MetroException) o)
                .filter(ex -> ex.getStatus() == HttpStatus.UNPROCESSABLE_ENTITY
                        && "REFUND_EXCEEDS_RECEIVED".equals(ex.getCode()))
                .count();
        assertEquals(3L, rejected, "超额确认必须被业务拒绝");

        // 总退款严格等于原实收，绝不超退
        Long refundedTotal = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(-amount_cents),0) FROM adjustment_detail WHERE txn_no = 'CT-2'",
                Long.class);
        assertEquals(500L, refundedTotal);

        // 各线路累计不超原份额
        Long l1 = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(-amount_cents),0) FROM adjustment_detail"
                        + " WHERE txn_no = 'CT-2' AND line_code = 'L1'", Long.class);
        Long l2 = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(-amount_cents),0) FROM adjustment_detail"
                        + " WHERE txn_no = 'CT-2' AND line_code = 'L2'", Long.class);
        assertTrue(l1 <= 188L, "L1 累计退款不得超过原份额 188: " + l1);
        assertTrue(l2 <= 312L, "L2 累计退款不得超过原份额 312: " + l2);
        assertEquals(500L, l1 + l2);
        Integer confirmedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_order WHERE txn_no = 'CT-2' AND status = 'CONFIRMED'",
                Integer.class);
        assertEquals(5, confirmedCount);
    }

    @Test
    void concurrentIncludesShareSingleOpenBatchAndNeverDoubleCount() throws Exception {
        sealedTrip("CT-3", "CC-3", "A", "D", "2026-09-25");
        applyRefund("ca-3a", "CR-3A", "CT-3", 100);
        applyRefund("ca-3b", "CR-3B", "CT-3", 200);
        String adjA = refundService.confirm(confirmRequest("ck-3a", "CR-3A", 1)).adjustmentNo();
        String adjB = refundService.confirm(confirmRequest("ck-3b", "CR-3B", 2)).adjustmentNo();

        // 两笔不同调账单并发纳入；另有同单重复纳入
        List<Object> outcomes = runConcurrently(4, i -> {
            try {
                String adjustmentNo = switch (i) {
                    case 0 -> adjA;
                    case 1 -> adjB;
                    case 2 -> adjA;
                    default -> adjB;
                };
                return refundService.include(new AdjustmentIncludeRequest(
                        "ik-3-" + i, adjustmentNo, 1));
            } catch (MetroException ex) {
                return ex;
            }
        });

        long errors = outcomes.stream().filter(o -> o instanceof MetroException).count();
        assertEquals(0L, errors, "重复纳入应幂等成功而非报错");

        Integer openBatches = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM adjustment_batch WHERE status = 'OPEN'", Integer.class);
        Integer totalBatches = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM adjustment_batch", Integer.class);
        assertEquals(1, openBatches, "并发纳入只允许自动创建一个开放批次");
        assertEquals(1, totalBatches);

        // 每个调账单只纳入一次，不重复计额
        Integer included = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM adjustment_order WHERE status = 'INCLUDED'", Integer.class);
        assertEquals(2, included);
        Long countedTwice = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT batch_id, adjustment_no, COUNT(*) c"
                        + " FROM adjustment_order GROUP BY batch_id, adjustment_no HAVING c > 1)",
                Long.class);
        assertEquals(0L, countedTwice);
        Long batchSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM adjustment_order WHERE status = 'INCLUDED'",
                Long.class);
        assertEquals(300L, batchSum, "同一调账单重复纳入不重复计额");
    }
}
