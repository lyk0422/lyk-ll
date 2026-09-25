package com.example.starter.invoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.example.starter.invoice.web.ApiException;
import com.example.starter.invoice.web.CreditAccountResponse;

/**
 * 并发开票测试：串行校验，合计不得超额。
 */
class ConcurrentIssueTest extends BaseIntegrationTest {

    @Test
    void 并发开票不得合计超额() throws Exception {
        createAccount(SELLER, "1000.00");
        int threads = 8;
        BigDecimal each = new BigDecimal("200.00");

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "REQ-CONC-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                    invoiceService.issue(issueRequest(requestId, SELLER, each.toPlainString()));
                    success.incrementAndGet();
                } catch (ApiException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未就序");
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // 总额度 1000，每张 200：恰有 5 张成功，其余因额度不足拒绝
        assertEquals(5, success.get());
        assertEquals(threads - 5, rejected.get());

        CreditAccountResponse account = creditAccountService.getBySellerTaxNo(SELLER);
        assertEquals(0, BigDecimal.ZERO.compareTo(account.availableAmount()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(account.blueUnarchivedAmount()));
        // 实际落库发票数与成功数一致，合计占用不超过总额度
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM invoice", Integer.class).intValue());
        BigDecimal occupied = jdbc.queryForObject("SELECT COALESCE(SUM(total_amount), 0) FROM invoice",
                BigDecimal.class);
        assertTrue(occupied.compareTo(new BigDecimal("1000.00")) <= 0);
    }
}
