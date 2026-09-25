package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.starter.domain.Models.BillStatus;
import com.example.starter.domain.Models.BillView;
import com.example.starter.domain.Models.CloseResult;
import com.example.starter.domain.Models.Party;
import com.example.starter.error.ApiException;
import com.example.starter.service.BillingService;
import org.junit.jupiter.api.Test;

/**
 * 并发边界：确认与异议竞态形成唯一结果；并发结案只结案一次；同键并发重放一致。
 * 使用门闩协调真实并发，全部等待带超时。
 */
class ConcurrencyTest extends BaseIntegrationTest {

    @Test
    void 并发确认与异议形成唯一结果() throws Exception {
        newTreaty("T-1", "0.8", "100000.00");
        newPolicy("T-1", "P-1", "1000.00");
        BillView bill = billingService.generate("rq-g1", "T-1", "2026Q3");
        String billNo = bill.bill().billNo();
        long itemId = bill.items().get(0).id();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Object> confirmFuture = pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(10, TimeUnit.SECONDS));
                return billingService.confirm("rq-conf", billNo, Party.REINSURER);
            });
            Future<Object> disputeFuture = pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(10, TimeUnit.SECONDS));
                return billingService.dispute("rq-disp", billNo,
                        List.of(new BillingService.DisputeItemInput(itemId, "金额不符")));
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();

            AtomicInteger conflicts = new AtomicInteger();
            Object confirmResult = await(confirmFuture, conflicts);
            Object disputeResult = await(disputeFuture, conflicts);
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

            // 恰好一方成功，另一方 409
            assertEquals(1, conflicts.get());
            assertTrue(confirmResult != null ^ disputeResult != null);

            BillView end = billingService.getBill(billNo);
            if (confirmResult != null) {
                // 确认先生效：已确认、未异议
                assertTrue(end.bill().confirmedByReinsurer());
                assertEquals(BillStatus.PENDING, end.bill().status());
                assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM bill_dispute", Long.class));
            } else {
                // 异议先生效：已异议、无任何确认
                assertEquals(BillStatus.DISPUTED, end.bill().status());
                assertTrue(!end.bill().confirmedByReinsurer() && !end.bill().confirmedByCedant());
                assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM bill_dispute", Long.class));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Object await(Future<Object> future, AtomicInteger conflicts) throws Exception {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof ApiException api && api.status().value() == 409) {
                conflicts.incrementAndGet();
                return null;
            }
            throw e;
        }
    }

    @Test
    void 并发结案同一赔案只结案一次() throws Exception {
        newTreaty("T-2", "0.8", "100000.00");
        newPolicy("T-2", "P-2", "0.00");
        claimService.register("rq-1", "P-2", "C-2", new BigDecimal("500.00"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<CloseResult> f1 = pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(10, TimeUnit.SECONDS));
                return claimService.close("rq-c1", "C-2", new BigDecimal("400.00"));
            });
            Future<CloseResult> f2 = pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(10, TimeUnit.SECONDS));
                return claimService.close("rq-c2", "C-2", new BigDecimal("400.00"));
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            CloseResult r1 = f1.get(30, TimeUnit.SECONDS);
            CloseResult r2 = f2.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

            // 恰好一次真实结案，另一次为终态重放
            assertTrue(r1.replayed() ^ r2.replayed());
            assertEquals(0, r1.paidAmount().compareTo(r2.paidAmount()));
            // 账户只记一条预提与一条冲销，余额 = 实际摊回 320
            assertEquals(2, treatyService.getAccount("T-2").entries().size());
            assertEquals(0, new BigDecimal("320.00").compareTo(balanceOf("T-2")));
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM claim WHERE claim_no = 'C-2' AND status = 'CLOSED'",
                    Long.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 并发同键出账返回同一账单() throws Exception {
        newTreaty("T-3", "0.8", "100000.00");
        newPolicy("T-3", "P-3", "1000.00");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<BillView> f1 = pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(10, TimeUnit.SECONDS));
                return billingService.generate("rq-same", "T-3", "2026Q3");
            });
            Future<BillView> f2 = pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(10, TimeUnit.SECONDS));
                return billingService.generate("rq-same", "T-3", "2026Q3");
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            BillView v1 = f1.get(30, TimeUnit.SECONDS);
            BillView v2 = f2.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

            assertNotNull(v1);
            assertEquals(v1.bill().billNo(), v2.bill().billNo());
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM bill", Long.class));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_key", Long.class));
        } finally {
            pool.shutdownNow();
        }
    }
}
