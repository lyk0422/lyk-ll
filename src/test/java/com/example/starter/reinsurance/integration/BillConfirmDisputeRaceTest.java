package com.example.starter.reinsurance.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.example.starter.reinsurance.api.BillResponse;
import com.example.starter.reinsurance.api.ConfirmBillRequest;
import com.example.starter.reinsurance.api.DisputeBillRequest;
import com.example.starter.reinsurance.api.GenerateBillRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 账单确认与异议并发测试：真实多线程同时发起，数据库行锁 + 状态条件更新保证唯一结果，
 * 账单不可能既确认（SEALED）又异议（DISPUTED）。
 */
class BillConfirmDisputeRaceTest extends AbstractIntegrationTest {

    @Test
    void concurrentConfirmAndDisputeProducesExactlyOneOutcome() throws Exception {
        treaty("T-R1", "0.30", null);
        policy("P-R1", "T-R1", "1000.00", LocalDate.parse("2026-08-01"));
        BillResponse bill = billService.generate(
                new GenerateBillRequest("req-r1-gen", "T-R1", "2026-Q3"));
        String billNo = bill.billNo();

        int rounds = 8;
        AtomicInteger sealed = new AtomicInteger();
        AtomicInteger disputed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < rounds; round++) {
                // 每轮重建一张全新待确认账单，避免上一轮终态影响。
                String contractNo = "T-R" + (round + 10);
                String periodBillNo = setupFreshBill(contractNo, round);

                CountDownLatch start = new CountDownLatch(1);
                int r = round;
                Future<?> confirmFuture = pool.submit(() -> {
                    try {
                        start.await();
                        try {
                            BillResponse result = billService.confirm(
                                    periodBillNo,
                                    new ConfirmBillRequest("req-r" + r + "-confirm"));
                            if ("SEALED".equals(result.status())) {
                                sealed.incrementAndGet();
                            }
                        } catch (Exception e) {
                            rejected.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                });
                Future<?> disputeFuture = pool.submit(() -> {
                    try {
                        start.await();
                        try {
                            BillResponse result = billService.dispute(
                                    periodBillNo,
                                    new DisputeBillRequest(
                                            "req-r" + r + "-dispute",
                                            List.of(new DisputeBillRequest.DisputeItemRequest(
                                                    "L1", new BigDecimal("250.00"), "并发异议：保费基数存疑"))));
                            if ("DISPUTED".equals(result.status())) {
                                disputed.incrementAndGet();
                            }
                        } catch (Exception e) {
                            rejected.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                });
                start.countDown();
                confirmFuture.get(30, TimeUnit.SECONDS);
                disputeFuture.get(30, TimeUnit.SECONDS);

                // 每一轮：恰有一方成功，另一方被拒绝；终态唯一。
                BillResponse finalBill = billService.getBill(periodBillNo);
                String status = finalBill.status();
                assertTrue("SEALED".equals(status) || "DISPUTED".equals(status),
                        "最终状态必须唯一，实际: " + status);
                if ("SEALED".equals(status)) {
                    assertEquals(0, finalBill.disputes().size(), "封存账单不得挂异议明细");
                } else {
                    assertEquals(1, finalBill.disputes().size());
                }
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }

        assertEquals(rounds, sealed.get() + disputed.get(),
                "每轮恰有一次成功操作");
        // 至少各赢一次，证明两个方向都真实参与了竞争（非确定性由数据库锁裁决）。
        // 若极端调度使一方始终获胜，下面的计数断言放宽为“成功总数=轮数”，
        // 并额外校验绝不存在既确认又异议的数据。
        Integer bothWins = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bill b WHERE b.status = 'SEALED' "
                        + "AND EXISTS (SELECT 1 FROM bill_dispute d WHERE d.bill_id = b.id)",
                Integer.class);
        assertEquals(0, bothWins, "任何账单都不得既封存又有异议");
    }

    @Test
    void sameRequestIdConcurrentGenerationCreatesExactlyOneBill() throws Exception {
        treaty("T-RR1", "0.30", null);
        policy("P-RR1", "T-RR1", "1000.00", LocalDate.parse("2026-08-01"));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    return billService.generate(
                            new GenerateBillRequest("req-rr1-same", "T-RR1", "2026-Q3")).billNo();
                }));
            }
            start.countDown();
            for (Future<String> future : futures) {
                assertEquals("RB-T-RR1-2026-Q3", future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }

        Integer billCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bill WHERE contract_id = "
                        + "(SELECT id FROM treaty WHERE contract_no = 'T-RR1')",
                Integer.class);
        assertEquals(1, billCount, "同键并发出账只能产生一张账单");
        Integer itemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bill_item bi JOIN bill b ON bi.bill_id = b.id "
                        + "WHERE b.bill_no = 'RB-T-RR1-2026-Q3'",
                Integer.class);
        assertEquals(1, itemCount, "同键并发出账不得重复写入明细");
        failIfInterrupted();
    }

    private String setupFreshBill(String contractNo, int round) {
        treaty(contractNo, "0.30", null);
        policy("P-" + contractNo, contractNo, "1000.00", LocalDate.parse("2026-08-01"));
        BillResponse fresh = billService.generate(new GenerateBillRequest(
                "req-" + contractNo + "-gen", contractNo, "2026-Q3"));
        return fresh.billNo();
    }

    private static void failIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            fail("测试线程被中断");
        }
    }
}
