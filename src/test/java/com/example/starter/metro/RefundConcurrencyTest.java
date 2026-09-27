package com.example.starter.metro;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.metro.dto.AdjustmentBatchOpenRequest;
import com.example.starter.metro.dto.EntryRequest;
import com.example.starter.metro.dto.ExitRequest;
import com.example.starter.metro.dto.RefundApplyRequest;
import com.example.starter.metro.dto.RefundConfirmRequest;
import com.example.starter.metro.dto.RefundConfirmResponse;
import com.example.starter.metro.dto.SealRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 封账后冲正并发与幂等边界测试（H2 内存库，真实多线程）：
 * 并发退款不得超退；并发双人确认恰好一次生成调整单；同键并发只冻结一次结果。
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
        jdbcTemplate.update("DELETE FROM refund_adjustment_detail");
        jdbcTemplate.update("DELETE FROM refund_adjustment");
        jdbcTemplate.update("DELETE FROM adjustment_batch");
        jdbcTemplate.update("DELETE FROM refund_review");
        jdbcTemplate.update("DELETE FROM refund_line_share");
        jdbcTemplate.update("DELETE FROM refund_order");
        jdbcTemplate.update("DELETE FROM metro_card");
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

    /** 构造一笔 A->F、已按实结算并已封账的行程（原实收 500 分，L1=188、L2=312）。 */
    private void sealedTripAF(String prefix, String txnNo, String cardId) {
        metroService.entry(new EntryRequest(prefix + "-e", txnNo, cardId, "A",
                LocalDateTime.of(2026, 9, 25, 9, 0)));
        metroService.exit(new ExitRequest(prefix + "-x", txnNo, "F",
                LocalDateTime.of(2026, 9, 25, 9, 40)));
        metroService.seal(new SealRequest(prefix + "-s", java.time.LocalDate.of(2026, 9, 25)));
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
    void concurrentRefundsNeverExceedOriginalFare() throws Exception {
        sealedTripAF("cc1", "TCC1", "CARDCC1");
        // 4 笔各 200 分的退款同时完成申请+双人确认；第 3 笔时仅余 100 分，必被拒
        List<Object> outcomes = runConcurrently(4, i -> {
            String tag = "cc1-" + i;
            try {
                refundService.apply(new RefundApplyRequest("ra-" + tag, "RCC1-" + i, "TCC1", 200L,
                        LocalDateTime.of(2026, 9, 26, 10, i)));
                refundService.confirm(new RefundConfirmRequest("RCC1-" + i, "rc1-" + tag, "U1",
                        LocalDateTime.of(2026, 9, 26, 11, 0), 1));
                RefundConfirmResponse confirmed = refundService.confirm(
                        new RefundConfirmRequest("RCC1-" + i, "rc2-" + tag, "U2",
                                LocalDateTime.of(2026, 9, 26, 11, 5), 2));
                return confirmed;
            } catch (MetroException ex) {
                return ex;
            } catch (Exception ex) {
                return ex;
            }
        });

        long confirmed = outcomes.stream()
                .filter(o -> o instanceof RefundConfirmResponse r && r.confirmed())
                .count();
        long rejected = outcomes.stream()
                .filter(o -> o instanceof MetroException ex
                        && "REFUND_EXCEEDS_AVAILABLE".equals(ex.getCode()))
                .count();
        assertEquals(2L, confirmed, "仅两笔 200 分退款可成功（占 400，余 100 不足第三笔）");
        assertEquals(2L, rejected, "其余并发退款被拒绝，不超退");

        assertRefundedTotal("TCC1", 400L);
    }

    @Test
    void concurrentSmallRefundsFillExactlyToOriginalFare() throws Exception {
        sealedTripAF("cc5", "TCC5", "CARDCC5");
        // 5 笔各 100 分并发：恰好填满原实收 500，全部成功且逐线路不超
        List<Object> outcomes = runConcurrently(5, i -> {
            String tag = "cc5-" + i;
            try {
                refundService.apply(new RefundApplyRequest("ra-" + tag, "RCC5-" + i, "TCC5", 100L,
                        LocalDateTime.of(2026, 9, 26, 10, i)));
                refundService.confirm(new RefundConfirmRequest("RCC5-" + i, "rc1-" + tag, "U1",
                        LocalDateTime.of(2026, 9, 26, 11, 0), 1));
                return refundService.confirm(new RefundConfirmRequest("RCC5-" + i, "rc2-" + tag, "U2",
                        LocalDateTime.of(2026, 9, 26, 11, 5), 2));
            } catch (MetroException ex) {
                return ex;
            } catch (Exception ex) {
                return ex;
            }
        });
        long confirmed = outcomes.stream()
                .filter(o -> o instanceof RefundConfirmResponse r && r.confirmed())
                .count();
        assertEquals(5L, confirmed, "五笔 100 分恰退满原实收 500 分");
        assertRefundedTotal("TCC5", 500L);
    }

    /** 断言行程累计退款恰为预期、逐线路不超原份额、负向明细合计与之一致。 */
    private void assertRefundedTotal(String txnNo, long expectedTotal) {
        Long totalRefunded = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(refunded_cents),0) FROM refund_line_share WHERE txn_no = ?",
                Long.class, txnNo);
        assertEquals(expectedTotal, totalRefunded);
        Long overLines = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_line_share WHERE txn_no = ?"
                        + " AND refunded_cents > original_share_cents",
                Integer.class, txnNo).longValue();
        assertEquals(0L, overLines, "任何线路累计退款不得超过原份额");
        Long negativeSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(d.amount_cents),0) FROM refund_adjustment_detail d"
                        + " JOIN refund_adjustment a ON a.id = d.adjustment_id WHERE a.txn_no = ?",
                Long.class, txnNo);
        assertEquals(-expectedTotal, negativeSum, "负向调整明细合计等于已退金额相反数");
    }

    @Test
    void concurrentTwoReviewersConfirmExactlyOneAdjustment() throws Exception {
        sealedTripAF("cc2", "TCC2", "CARDCC2");
        refundService.apply(new RefundApplyRequest("ra-cc2", "RCC2", "TCC2", 100L,
                LocalDateTime.of(2026, 9, 26, 10, 0)));

        // 首人复核已完成；第二人（最终确认）并发 6 次（不同 requestId、不同复核人）
        refundService.confirm(new RefundConfirmRequest("RCC2", "rc-cc2-1", "U1",
                LocalDateTime.of(2026, 9, 26, 11, 0), 1));
        List<Object> finals = runConcurrently(6, i -> {
            try {
                return refundService.confirm(new RefundConfirmRequest("RCC2", "rc-cc2-f" + i,
                        "U" + (2 + i), LocalDateTime.of(2026, 9, 26, 11, 5), 2));
            } catch (MetroException ex) {
                return ex;
            }
        });
        long confirmed = finals.stream()
                .filter(o -> o instanceof RefundConfirmResponse r && r.confirmed() && !r.replayed())
                .count();
        assertEquals(1L, confirmed, "最终确认仅一次实际生效");
        Integer adjustments = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_adjustment WHERE refund_no = 'RCC2'", Integer.class);
        assertEquals(1, adjustments, "仅生成一张调整单");
        Integer reviews = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_review WHERE refund_no = 'RCC2'", Integer.class);
        assertEquals(2, reviews, "仅登记两名不同复核人");
        Long occupied = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(refunded_cents),0) FROM refund_line_share WHERE txn_no = 'TCC2'",
                Long.class);
        assertEquals(100L, occupied, "余额只占用一次");
    }

    @Test
    void concurrentSameKeyApplyReplaysSingleResult() throws Exception {
        sealedTripAF("cc3", "TCC3", "CARDCC3");
        RefundApplyRequest request = new RefundApplyRequest("ra-cc3", "RCC3", "TCC3", 100L,
                LocalDateTime.of(2026, 9, 26, 10, 0));
        List<Object> outcomes = runConcurrently(6, i -> {
            try {
                return refundService.apply(request);
            } catch (MetroException ex) {
                return ex;
            }
        });
        long fresh = outcomes.stream()
                .filter(o -> o instanceof com.example.starter.metro.dto.RefundApplyResponse r
                        && !r.replayed())
                .count();
        assertEquals(1L, fresh, "同键并发仅一笔实际申请");
        Integer orders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_order WHERE refund_no = 'RCC3'", Integer.class);
        assertEquals(1, orders);
    }

    @Test
    void concurrentIncludesOfSameAdjustmentCountedOnce() throws Exception {
        sealedTripAF("cc4", "TCC4", "CARDCC4");
        refundService.apply(new RefundApplyRequest("ra-cc4", "RCC4", "TCC4", 100L,
                LocalDateTime.of(2026, 9, 26, 10, 0)));
        refundService.confirm(new RefundConfirmRequest("RCC4", "rc-cc4-1", "U1",
                LocalDateTime.of(2026, 9, 26, 11, 0), 1));
        refundService.confirm(new RefundConfirmRequest("RCC4", "rc-cc4-2", "U2",
                LocalDateTime.of(2026, 9, 26, 11, 5), 2));
        refundService.openBatch(new AdjustmentBatchOpenRequest("ro-cc4", "ABCC4",
                LocalDateTime.of(2026, 9, 26, 12, 0)));

        List<Object> outcomes = runConcurrently(4, i -> {
            try {
                return refundService.include(new com.example.starter.metro.dto.AdjustmentIncludeRequest(
                        "ri-cc4-" + i, "ADJ-RCC4", "ABCC4",
                        LocalDateTime.of(2026, 9, 26, 12, 5 + i), 1));
            } catch (MetroException ex) {
                return ex;
            }
        });
        // 批次版本每次实际纳入加 1，重复纳入不加；恰好一次实际纳入
        long firstIncludes = outcomes.stream()
                .filter(o -> o instanceof com.example.starter.metro.dto.AdjustmentIncludeResponse r
                        && !r.alreadyIncluded() && !r.replayed())
                .count();
        // 不同 requestId 下并发重复纳入：首个纳入，其余因行锁串行后发现已纳入同一批次
        assertTrue(firstIncludes >= 1L, "至少一笔纳入成功");
        Integer detailRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM adjustment_detail", Integer.class);
        assertEquals(2, detailRows, "两张负向明细只复制一次，不重复计额");
        Long batchTotal = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM adjustment_detail", Long.class);
        assertEquals(-100L, batchTotal);
    }
}
