package com.example.starter.metro;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.metro.dto.RemittanceOpenRequest;
import com.example.starter.metro.dto.RemittanceReceiptRequest;
import com.example.starter.metro.dto.RemittanceReceiptResponse;
import com.example.starter.metro.dto.EntryRequest;
import com.example.starter.metro.dto.ExitRequest;
import com.example.starter.metro.dto.RefundApplyRequest;
import com.example.starter.metro.dto.RefundConfirmRequest;
import com.example.starter.metro.dto.SealRequest;
import com.example.starter.metro.dto.AdjustmentBatchOpenRequest;
import com.example.starter.metro.dto.AdjustmentIncludeRequest;
import com.example.starter.metro.dto.AdjustmentSealRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缴回对账并发与幂等边界测试（H2 内存库，真实多线程与事务）：
 * 并发回执争抢版本/余额不得超缴；同 requestId、同银行流水（异 requestId）并发只入账一次；
 * 逐线路条件 UPDATE 在数据库边界直接拒绝超缴。
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
    private RemittanceRepository remittanceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM remittance_receipt_detail");
        jdbcTemplate.update("DELETE FROM remittance_receipt");
        jdbcTemplate.update("DELETE FROM remittance_line");
        jdbcTemplate.update("DELETE FROM remittance_source_detail");
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

    /** 构造完整链路并建立缴回对账单；应缴=refundAmount（L1/L2 为退款分摊绝对值）。 */
    private String openRemittance(String prefix, long refundAmount, int day) {
        metroService.entry(new EntryRequest(prefix + "-e", "TX-" + prefix, "CARD-" + prefix, "A",
                LocalDateTime.of(2026, 9, day, 9, 0)));
        metroService.exit(new ExitRequest(prefix + "-x", "TX-" + prefix, "F",
                LocalDateTime.of(2026, 9, day, 9, 40)));
        metroService.seal(new SealRequest(prefix + "-s", java.time.LocalDate.of(2026, 9, day)));
        String refundNo = "R-" + prefix;
        refundService.apply(new RefundApplyRequest(prefix + "-ra", refundNo, "TX-" + prefix,
                refundAmount, LocalDateTime.of(2026, 9, day, 10, 0)));
        refundService.confirm(new RefundConfirmRequest(refundNo, prefix + "-rc1", "U1",
                LocalDateTime.of(2026, 9, day, 11, 0), 1));
        refundService.confirm(new RefundConfirmRequest(refundNo, prefix + "-rc2", "U2",
                LocalDateTime.of(2026, 9, day, 11, 5), 2));
        String batchNo = "AB-" + prefix;
        refundService.openBatch(new AdjustmentBatchOpenRequest(prefix + "-ro", batchNo,
                LocalDateTime.of(2026, 9, day, 12, 0)));
        refundService.include(new AdjustmentIncludeRequest(prefix + "-ri", "ADJ-" + refundNo,
                batchNo, LocalDateTime.of(2026, 9, day, 12, 5), 1));
        refundService.sealBatch(new AdjustmentSealRequest(prefix + "-rs", batchNo,
                LocalDateTime.of(2026, 9, day, 13, 0), 2));
        String statementNo = "RM-" + prefix;
        remittanceService.open(new RemittanceOpenRequest(prefix + "-rmo", statementNo, batchNo,
                LocalDateTime.of(2026, 9, day, 14, 0)));
        return statementNo;
    }

    private RemittanceReceiptRequest receipt(String requestId, String statementNo, String serial,
                                             long total, List<RemittanceReceiptRequest.Allocation> alloc,
                                             int expectedVersion) {
        return new RemittanceReceiptRequest(requestId, statementNo, serial,
                Instant.parse("2026-09-27T01:00:00Z"), total, alloc, expectedVersion);
    }

    private List<RemittanceReceiptRequest.Allocation> l1l2(long l1, long l2) {
        List<RemittanceReceiptRequest.Allocation> alloc = new ArrayList<>();
        if (l1 > 0) {
            alloc.add(new RemittanceReceiptRequest.Allocation("L1", l1));
        }
        if (l2 > 0) {
            alloc.add(new RemittanceReceiptRequest.Allocation("L2", l2));
        }
        return alloc;
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

    private long paidOf(String statementNo) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(paid_cents),0) FROM remittance_line WHERE statement_id ="
                        + " (SELECT id FROM remittance_statement WHERE statement_no = ?)",
                Long.class, statementNo);
    }

    private int versionOf(String statementNo) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM remittance_statement WHERE statement_no = ?",
                Integer.class, statementNo);
    }

    @Test
    void concurrentDifferentReceiptsCompetingVersionSucceedsExactlyOnce() throws Exception {
        String rm = openRemittance("c1", 300, 20);
        // 8 笔不同流水、各 100（L1=40,L2=60），同 expectedVersion=1 并发
        List<Object> outcomes = runConcurrently(8, i -> {
            try {
                return remittanceService.submitReceipt(receipt("c1-rr-" + i, rm, "BK-C1-" + i,
                        100, l1l2(40, 60), 1));
            } catch (MetroException ex) {
                return ex;
            }
        });
        long fresh = outcomes.stream()
                .filter(o -> o instanceof RemittanceReceiptResponse r && !r.replayed())
                .count();
        long conflicts = outcomes.stream()
                .filter(o -> o instanceof MetroException ex
                        && "VERSION_CONFLICT".equals(ex.getCode()))
                .count();
        assertEquals(1L, fresh, "并发同版本仅一笔实际入账");
        assertEquals(7L, conflicts, "其余因乐观版本冲突被拒绝");
        assertEquals(100L, paidOf(rm), "不超缴");
        assertEquals(2, versionOf(rm), "版本只加一次");
    }

    @Test
    void concurrentReceiptsRacingToPayOffCompleteExactlyOnce() throws Exception {
        // 应缴 100（L1=37,L2=63）：8 笔各想全额缴齐并发，仅一笔成功并完成
        String rm = openRemittance("c2", 100, 21);
        List<Object> outcomes = runConcurrently(8, i -> {
            try {
                return remittanceService.submitReceipt(receipt("c2-rr-" + i, rm, "BK-C2-" + i,
                        100, l1l2(37, 63), 1));
            } catch (MetroException ex) {
                return ex;
            }
        });
        long completed = outcomes.stream()
                .filter(o -> o instanceof RemittanceReceiptResponse r
                        && "COMPLETED".equals(r.status()) && !r.replayed())
                .count();
        assertEquals(1L, completed, "恰一笔缴齐并完成");
        assertEquals(100L, paidOf(rm));
        assertEquals("COMPLETED", jdbcTemplate.queryForObject(
                "SELECT status FROM remittance_statement WHERE statement_no = ?", String.class, rm));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM remittance_receipt r JOIN remittance_statement s"
                        + " ON r.statement_id = s.id WHERE s.statement_no = ?", Integer.class, rm),
                "仅一笔流水入账");
    }

    @Test
    void concurrentSameRequestIdPostsOnlyOnce() throws Exception {
        String rm = openRemittance("c3", 300, 22);
        var request = receipt("c3-same-key", rm, "BK-C3", 100, l1l2(40, 60), 1);
        List<Object> outcomes = runConcurrently(6, i -> {
            try {
                return remittanceService.submitReceipt(request);
            } catch (MetroException ex) {
                return ex;
            }
        });
        long fresh = outcomes.stream()
                .filter(o -> o instanceof RemittanceReceiptResponse r && !r.replayed()).count();
        long replayed = outcomes.stream()
                .filter(o -> o instanceof RemittanceReceiptResponse r && r.replayed()).count();
        assertEquals(1L, fresh);
        assertEquals(5L, replayed);
        assertEquals(100L, paidOf(rm), "同 requestId 并发不重复入账");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM remittance_receipt WHERE bank_serial_no = 'BK-C3'",
                Integer.class));
    }

    @Test
    void concurrentSameBankSerialDifferentRequestIdsPostsOnlyOnce() throws Exception {
        String rm = openRemittance("c4", 300, 23);
        // 同流水号、同完整内容、不同 requestId 并发：仅一笔实际入账，其余重放绑定各自 requestId
        List<Object> outcomes = runConcurrently(6, i -> receipt("c4-key-" + i, rm, "BK-C4",
                100, l1l2(40, 60), 1))
                .stream().map(request -> {
                    try {
                        return (Object) remittanceService.submitReceipt(request);
                    } catch (MetroException ex) {
                        return ex;
                    }
                }).toList();
        long fresh = outcomes.stream()
                .filter(o -> o instanceof RemittanceReceiptResponse r && !r.replayed()).count();
        long replayed = outcomes.stream()
                .filter(o -> o instanceof RemittanceReceiptResponse r && r.replayed()).count();
        assertEquals(1L, fresh, "同流水仅一笔实际入账");
        assertEquals(5L, replayed, "其余以新 requestId 重放首次结果");
        assertEquals(100L, paidOf(rm), "不重复入账");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM remittance_receipt WHERE bank_serial_no = 'BK-C4'",
                Integer.class), "全局唯一流水仅一行");
    }

    @Test
    void conditionalLineUpdateRejectsConcurrentOverpayAtDatabaseBoundary() throws Exception {
        // 应缴 100：L1 应缴 37；两个独立事务并发各缴 37，数据库条件 UPDATE 只允许一个成功
        String rm = openRemittance("c5", 100, 24);
        Long statementId = jdbcTemplate.queryForObject(
                "SELECT id FROM remittance_statement WHERE statement_no = ?", Long.class, rm);
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        List<Integer> results = runConcurrently(2, i -> txTemplate.execute(status ->
                remittanceRepository.payLine(statementId, "L1", 37, LocalDateTime.now())));
        long succeeded = results.stream().filter(r -> r == 1).count();
        long rejected = results.stream().filter(r -> r == 0).count();
        assertEquals(1L, succeeded, "并发各缴 37（应缴 37）仅一个条件更新成功");
        assertEquals(1L, rejected, "另一个因 paid+37>due 被数据库拒绝");
        Long l1Paid = jdbcTemplate.queryForObject(
                "SELECT paid_cents FROM remittance_line WHERE statement_id = ? AND line_code = 'L1'",
                Long.class, statementId);
        assertEquals(37L, l1Paid, "L1 恰缴 37，绝不超缴");
    }
}
