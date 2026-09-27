package com.example.starter.metro;

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
import com.example.starter.metro.dto.RefundApplyRequest;
import com.example.starter.metro.dto.RefundApplyResponse;
import com.example.starter.metro.dto.RefundConfirmRequest;
import com.example.starter.metro.dto.RefundConfirmResponse;
import com.example.starter.metro.dto.SealRequest;
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
 * 退款冲正并发边界测试：真实多线程并发双人确认，验证同键只生效一次、
 * 不同退款单并发绝不超退、行锁串行化下最终余额与调整合计一致。
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

    private void prepareSealedTrip(String txnNo) {
        metroService.entry(new EntryRequest("e-" + txnNo, txnNo, "C-" + txnNo, "A",
                LocalDateTime.of(2026, 9, 25, 9, 0)));
        metroService.exit(new ExitRequest("x-" + txnNo, txnNo, "F",
                LocalDateTime.of(2026, 9, 25, 9, 40)));
        metroService.seal(new SealRequest("s-" + txnNo, java.time.LocalDate.of(2026, 9, 25)));
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

    @Test
    void concurrentSameKeyConfirmAppliesExactlyOnce() throws Exception {
        prepareSealedTrip("T1");
        RefundApplyResponse applied = refundService.apply(new RefundApplyRequest("ra-c1", "T1",
                100, "x", "alice"));
        RefundConfirmRequest request = new RefundConfirmRequest("rc-c1", applied.refundNo(),
                "bob", "carol", 1, LocalDateTime.of(2026, 9, 26, 10, 0));

        List<RefundConfirmResponse> responses = runConcurrently(8, i -> refundService.confirm(request));
        long fresh = responses.stream().filter(r -> !r.replayed()).count();
        assertEquals(1L, fresh, "恰好一个请求实际确认，其余为重放");
        for (RefundConfirmResponse response : responses) {
            assertEquals("CONFIRMED", response.status());
            assertEquals(applied.refundNo(), response.refundNo());
        }
        Long orderCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM adjustment_order", Long.class);
        assertEquals(1L, orderCount);
        Long detailSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM adjustment_detail", Long.class);
        assertEquals(-100L, detailSum);
    }

    @Test
    void concurrentDistinctRefundsNeverOverRefund() throws Exception {
        prepareSealedTrip("T2");
        // 行程原实收 500 分；8 笔各 100 分的退款单并发确认，只允许 5 笔成功
        List<String> refundNos = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            RefundApplyResponse applied = refundService.apply(new RefundApplyRequest("ra-c2-" + i,
                    "T2", 100, "x", "alice"));
            refundNos.add(applied.refundNo());
        }
        List<Object> outcomes = runConcurrently(8, i -> {
            try {
                return refundService.confirm(new RefundConfirmRequest("rc-c2-" + i, refundNos.get(i),
                        "rev" + (i % 4), "rev" + ((i + 1) % 4) + "b", 1,
                        LocalDateTime.of(2026, 9, 26, 10, i)));
            } catch (MetroException ex) {
                return ex;
            }
        });

        long successes = outcomes.stream().filter(o -> o instanceof RefundConfirmResponse).count();
        assertEquals(5L, successes, "500/100：恰好 5 笔成功，其余超退拒绝");
        long rejections = outcomes.stream()
                .filter(o -> o instanceof MetroException)
                .map(o -> (MetroException) o)
                .filter(ex -> ex.getStatus() == HttpStatus.UNPROCESSABLE_ENTITY
                        && "REFUND_EXCEEDS_FARE".equals(ex.getCode()))
                .count();
        assertEquals(3L, rejections);

        // 被拒退款单仍为 PENDING，成功单为 CONFIRMED 且版本为 2
        Integer confirmed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_order WHERE status = 'CONFIRMED' AND version = 2",
                Integer.class);
        assertEquals(5, confirmed);
        Integer pending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_order WHERE status = 'PENDING'", Integer.class);
        assertEquals(3, pending);

        // 调整明细合计恰为 -500，逐线路不超原份额 L1=188 L2=312
        Long detailSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM adjustment_detail", Long.class);
        assertEquals(-500L, detailSum);
        Long l1Refunded = jdbcTemplate.queryForObject(
                "SELECT -COALESCE(SUM(amount_cents),0) FROM adjustment_detail WHERE line_code = 'L1'",
                Long.class);
        Long l2Refunded = jdbcTemplate.queryForObject(
                "SELECT -COALESCE(SUM(amount_cents),0) FROM adjustment_detail WHERE line_code = 'L2'",
                Long.class);
        assertEquals(188L, l1Refunded);
        assertEquals(312L, l2Refunded);
        // 余额归零
        assertEquals(0L, refundService.balance("T2").remainingCents());
    }
}
