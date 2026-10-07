package com.example.starter.metro;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.metro.dto.AdjustmentBatchOpenRequest;
import com.example.starter.metro.dto.AdjustmentIncludeRequest;
import com.example.starter.metro.dto.AdjustmentSealRequest;
import com.example.starter.metro.dto.EntryRequest;
import com.example.starter.metro.dto.ExitRequest;
import com.example.starter.metro.dto.RefundApplyRequest;
import com.example.starter.metro.dto.RefundConfirmRequest;
import com.example.starter.metro.dto.RemittanceReceiptRequest;
import com.example.starter.metro.dto.RemittanceReceiptResponse;
import com.example.starter.metro.dto.RemittanceStatementOpenRequest;
import com.example.starter.metro.dto.RemittanceStatementOpenResponse;
import com.example.starter.metro.dto.RemittanceStatementView;
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
 * 缴回对账并发与幂等边界测试（H2 内存库，真实多线程）：
 * 并发回执争抢余额不得超缴；同流水并发只入账一次；每批最多一单并发建立仅一单；
 * 并发分次缴款恰好缴齐才完成。
 */
@SpringBootTest
class RemittanceConcurrencyTest {

    @Autowired
    private MetroService metroService;

    @Autowired
    private RefundService refundService;

    @Autowired
    private RemittanceService remittanceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM remittance_receipt_line");
        jdbcTemplate.update("DELETE FROM remittance_receipt");
        jdbcTemplate.update("DELETE FROM remittance_source_detail");
        jdbcTemplate.update("DELETE FROM remittance_statement_line");
        jdbcTemplate.update("DELETE FROM remittance_statement");
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

    /**
     * 构造已封存调账批次并建立对账单：行程 A->F 500 分全额退款
     * （L1=188、L2=312），对账单应缴合计 500 分。
     */
    private void statementWithDue500(String prefix, String txnNo, String batchNo,
                                     String statementNo) {
        metroService.entry(new EntryRequest(prefix + "-e", txnNo, "CARD-" + prefix, "A",
                LocalDateTime.of(2026, 9, 25, 9, 0)));
        metroService.exit(new ExitRequest(prefix + "-x", txnNo, "F",
                LocalDateTime.of(2026, 9, 25, 9, 40)));
        metroService.seal(new SealRequest(prefix + "-s", LocalDate.of(2026, 9, 25)));
        refundService.apply(new RefundApplyRequest(prefix + "-ra", "R-" + prefix, txnNo, 500L,
                LocalDateTime.of(2026, 9, 26, 10, 0)));
        refundService.confirm(new RefundConfirmRequest("R-" + prefix, prefix + "-rc1", "U1",
                LocalDateTime.of(2026, 9, 26, 11, 0), 1));
        refundService.confirm(new RefundConfirmRequest("R-" + prefix, prefix + "-rc2", "U2",
                LocalDateTime.of(2026, 9, 26, 11, 5), 2));
        refundService.openBatch(new AdjustmentBatchOpenRequest(prefix + "-ro", batchNo,
                LocalDateTime.of(2026, 9, 26, 12, 0)));
        refundService.include(new AdjustmentIncludeRequest(prefix + "-ri", "ADJ-R-" + prefix,
                batchNo, LocalDateTime.of(2026, 9, 26, 12, 5), 1));
        refundService.sealBatch(new AdjustmentSealRequest(prefix + "-rs", batchNo,
                LocalDateTime.of(2026, 9, 26, 13, 0), 2));
        RemittanceStatementOpenResponse opened = remittanceService.openStatement(
                new RemittanceStatementOpenRequest(prefix + "-mo", statementNo, batchNo,
                        LocalDateTime.of(2026, 9, 27, 9, 0)));
        assertEquals(500L, opened.dueTotalCents());
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
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    /** 带版本重试的回执：版本冲突时重读当前版本重试，业务拒绝则返回异常。 */
    private Object receiptWithRetry(String tag, String statementNo, String batchNo, String flowNo,
                                    long l1, long l2) {
        for (int attempt = 0; attempt < 10; attempt++) {
            int version = remittanceService.statementView(statementNo).version();
            try {
                return remittanceService.receipt(new RemittanceReceiptRequest("rq-" + tag + "-" + attempt,
                        statementNo, batchNo, flowNo, Instant.parse("2026-09-27T01:00:00Z"),
                        l1 + l2, version, List.of(
                                new RemittanceReceiptRequest.LineAllocation("L1", l1),
                                new RemittanceReceiptRequest.LineAllocation("L2", l2))));
            } catch (MetroException ex) {
                if ("VERSION_CONFLICT".equals(ex.getCode())) {
                    continue;
                }
                return ex;
            }
        }
        return new IllegalStateException("版本冲突重试耗尽: " + tag);
    }

    @Test
    void concurrentReceiptsRaceForBalanceNeverOverpay() throws Exception {
        statementWithDue500("cc1", "TRC1", "ABC1", "SMC1");
        // 4 笔各 200 分（L1=75、L2=125）并发争抢：恰两笔成功（400），其余超缴被拒
        List<Object> outcomes = runConcurrently(4, i ->
                receiptWithRetry("cc1-" + i, "SMC1", "ABC1", "FLOW-CC1-" + i, 75, 125));

        long succeeded = outcomes.stream()
                .filter(o -> o instanceof RemittanceReceiptResponse r && !r.replayed())
                .count();
        long overpaid = outcomes.stream()
                .filter(o -> o instanceof MetroException ex && "REMIT_OVERPAY".equals(ex.getCode()))
                .count();
        assertEquals(2L, succeeded, "恰两笔 200 分入账（400），余 100 不足第三笔");
        assertEquals(2L, overpaid, "其余争抢余额的回执整笔零入账");

        RemittanceStatementView view = remittanceService.statementView("SMC1");
        assertEquals(400L, view.paidTotalCents());
        assertEquals(100L, view.balanceCents());
        for (RemittanceStatementView.LineBalance line : view.lines()) {
            assertTrue(line.paidCents() <= line.dueCents(), "任何线路已缴不得超过应缴");
        }
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM remittance_receipt", Integer.class));
        Long paidSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM remittance_receipt_line", Long.class);
        assertEquals(400L, paidSum, "分配链合计与已缴一致，不超缴");
    }

    @Test
    void concurrentSameBankFlowPostedExactlyOnce() throws Exception {
        statementWithDue500("cc2", "TRC2", "ABC2", "SMC2");
        // 同流水同完整内容、不同 requestId 并发：只入账一次，其余重放首次结果
        List<Object> outcomes = runConcurrently(6, i -> {
            try {
                return remittanceService.receipt(new RemittanceReceiptRequest("rq-cc2-" + i,
                        "SMC2", "ABC2", "FLOW-CC2", Instant.parse("2026-09-27T01:00:00Z"),
                        100L, 1, List.of(new RemittanceReceiptRequest.LineAllocation("L1", 100L))));
            } catch (MetroException ex) {
                return ex;
            }
        });
        long fresh = outcomes.stream()
                .filter(o -> o instanceof RemittanceReceiptResponse r && !r.replayed())
                .count();
        long replayed = outcomes.stream()
                .filter(o -> o instanceof RemittanceReceiptResponse r && r.replayed())
                .count();
        assertEquals(1L, fresh, "同流水并发仅一笔实际入账");
        assertEquals(5L, replayed, "其余并发同流水重放首次结果");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM remittance_receipt", Integer.class));
        RemittanceStatementView view = remittanceService.statementView("SMC2");
        assertEquals(100L, view.paidTotalCents(), "同流水只入账一次，不重复计额");
        assertEquals(2, view.version());
    }

    @Test
    void concurrentStatementOpenAllowsExactlyOnePerBatch() throws Exception {
        statementWithDue500("cc3", "TRC3", "ABC3", "SMC3");
        // 同批已有一单；再并发建立（不同单号）全部拒绝
        List<Object> outcomes = runConcurrently(4, i -> {
            try {
                return remittanceService.openStatement(new RemittanceStatementOpenRequest(
                        "mo-cc3-" + i, "SMC3-X" + i, "ABC3",
                        LocalDateTime.of(2026, 9, 27, 10, i)));
            } catch (MetroException ex) {
                return ex;
            }
        });
        long succeeded = outcomes.stream()
                .filter(o -> o instanceof RemittanceStatementOpenResponse)
                .count();
        long exists = outcomes.stream()
                .filter(o -> o instanceof MetroException ex
                        && "REMIT_STATEMENT_EXISTS".equals(ex.getCode()))
                .count();
        assertEquals(0L, succeeded);
        assertEquals(4L, exists, "每批最多一单，并发建立全部拒绝");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM remittance_statement", Integer.class));
    }

    @Test
    void concurrentPartialReceiptsCompleteExactlyOnce() throws Exception {
        statementWithDue500("cc4", "TRC4", "ABC4", "SMC4");
        // 5 笔分次缴款恰好缴齐：4 笔 [L1=37,L2=63] + 1 笔 [L1=40,L2=60]，合计 L1=188、L2=312
        List<Object> outcomes = runConcurrently(5, i -> {
            long l1 = i < 4 ? 37 : 40;
            long l2 = i < 4 ? 63 : 60;
            return receiptWithRetry("cc4-" + i, "SMC4", "ABC4", "FLOW-CC4-" + i, l1, l2);
        });
        long succeeded = outcomes.stream()
                .filter(o -> o instanceof RemittanceReceiptResponse r && !r.replayed())
                .count();
        assertEquals(5L, succeeded, "五笔分次缴款全部成功，恰缴齐 500 分");

        RemittanceStatementView view = remittanceService.statementView("SMC4");
        assertEquals("COMPLETED", view.status());
        assertEquals(500L, view.paidTotalCents());
        assertEquals(0L, view.balanceCents());
        assertEquals(6, view.version(), "版本 1 起，每笔成功回执加 1");
        for (RemittanceStatementView.LineBalance line : view.lines()) {
            assertEquals(0L, line.balanceCents());
        }
        // 完成后新流水拒绝
        MetroException rejected = null;
        try {
            remittanceService.receipt(new RemittanceReceiptRequest("rq-cc4-late", "SMC4", "ABC4",
                    "FLOW-CC4-LATE", Instant.parse("2026-09-27T02:00:00Z"), 1L, 6,
                    List.of(new RemittanceReceiptRequest.LineAllocation("L1", 1L))));
        } catch (MetroException ex) {
            rejected = ex;
        }
        assertTrue(rejected != null && "REMIT_STATEMENT_COMPLETED".equals(rejected.getCode()),
                "全部缴齐后新流水拒绝");
        // 原调账快照未被覆盖
        Long adjTotal = jdbcTemplate.queryForObject(
                "SELECT total_cents FROM adjustment_batch WHERE batch_no = 'ABC4'", Long.class);
        assertEquals(-500L, adjTotal);
    }
}
