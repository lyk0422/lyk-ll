package com.example.starter.tank;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.web.BusinessRejectedException;
import com.example.starter.web.VersionConflictException;
import com.example.starter.web.dto.InjectionCancelRequest;
import com.example.starter.web.dto.InjectionPlanRequest;
import com.example.starter.web.dto.InjectionPlanResponse;
import com.example.starter.web.dto.InjectionReceiptRequest;
import com.example.starter.web.dto.RefillConfirmRequest;
import com.example.starter.web.dto.TankStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 储罐并发边界：补液与消耗、取消与回执、多计划预占并发时保持唯一计量，
 * 不超罐、不重复返还、不突破安全储备。
 */
class TankConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private TankService tankService;
    @Autowired
    private InjectionPlanService planService;
    @Autowired
    private RefillService refillService;

    @Test
    void concurrentRefillAndConsumeKeepUniqueMetering() throws Exception {
        TankFixtures.registerStandard(tankService);
        InjectionPlanResponse planned = planService.plan(new InjectionPlanRequest(
                TankFixtures.STATION, TankFixtures.nextRequestId("PLAN"),
                new BigDecimal("30"), TankFixtures.T, 1));

        // 回执消耗30 与 补液20 并发，均携带版本2：恰一个成功，另一个版本冲突
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Object> receiptFuture = pool.submit(() -> {
            start.await();
            try {
                return planService.receipt(new InjectionReceiptRequest(
                        TankFixtures.nextRequestId("RCPT"), planned.planId(),
                        new BigDecimal("30"), TankFixtures.T.plusHours(1), 2));
            } catch (VersionConflictException conflict) {
                return conflict;
            }
        });
        Future<Object> refillFuture = pool.submit(() -> {
            start.await();
            try {
                return refillService.confirm(new RefillConfirmRequest(TankFixtures.STATION,
                        TankFixtures.nextRequestId("REFILL"), "BATCH-C1", new BigDecimal("20"),
                        new BigDecimal("20.000"), "OP-A", "OP-B",
                        TankFixtures.T.plusHours(1), 2));
            } catch (VersionConflictException conflict) {
                return conflict;
            }
        });
        start.countDown();
        Object receiptResult = receiptFuture.get(15, TimeUnit.SECONDS);
        Object refillResult = refillFuture.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        int conflicts = (receiptResult instanceof VersionConflictException ? 1 : 0)
                + (refillResult instanceof VersionConflictException ? 1 : 0);
        assertEquals(1, conflicts, "同一版本并发变更恰一个成功");

        // 冲突方读取新版本3重试（新幂等键），两笔都入账
        if (receiptResult instanceof VersionConflictException) {
            planService.receipt(new InjectionReceiptRequest(
                    TankFixtures.nextRequestId("RCPT"), planned.planId(),
                    new BigDecimal("30"), TankFixtures.T.plusHours(1), 3));
        } else {
            refillService.confirm(new RefillConfirmRequest(TankFixtures.STATION,
                    TankFixtures.nextRequestId("REFILL"), "BATCH-C1", new BigDecimal("20"),
                    new BigDecimal("20.000"), "OP-A", "OP-B",
                    TankFixtures.T.plusHours(1), 3));
        }

        // 唯一计量：余额 = 50 - 30 + 20 = 40，预占 0，版本 4
        TankStatusResponse status = tankService.status(TankFixtures.STATION);
        assertEquals(0, status.balanceKg().compareTo(new BigDecimal("40.000")));
        assertEquals(0, status.reservedKg().compareTo(BigDecimal.ZERO.setScale(3)));
        assertEquals(4, status.version());
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'CONSUME'"));
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'REFILL'"));
    }

    @Test
    void concurrentCancelAndReceiptSettleExactlyOnce() throws Exception {
        TankFixtures.registerStandard(tankService);
        InjectionPlanResponse planned = planService.plan(new InjectionPlanRequest(
                TankFixtures.STATION, TankFixtures.nextRequestId("PLAN"),
                new BigDecimal("30"), TankFixtures.T, 1));

        // 取消与回执并发：恰一个生效，不重复返还也不重复扣量
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<String> cancelFuture = pool.submit(() -> {
            start.await();
            try {
                planService.cancel(new InjectionCancelRequest(
                        TankFixtures.nextRequestId("CNCL"), planned.planId(),
                        TankFixtures.T.plusHours(1), 2));
                return "CANCEL_OK";
            } catch (BusinessRejectedException | VersionConflictException rejected) {
                return "REJECTED";
            }
        });
        Future<String> receiptFuture = pool.submit(() -> {
            start.await();
            try {
                planService.receipt(new InjectionReceiptRequest(
                        TankFixtures.nextRequestId("RCPT"), planned.planId(),
                        new BigDecimal("20"), TankFixtures.T.plusHours(1), 2));
                return "RECEIPT_OK";
            } catch (BusinessRejectedException | VersionConflictException rejected) {
                return "REJECTED";
            }
        });
        start.countDown();
        String cancelResult = cancelFuture.get(15, TimeUnit.SECONDS);
        String receiptResult = receiptFuture.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, List.of(cancelResult, receiptResult).stream()
                .filter(r -> r.endsWith("_OK")).count(), "取消与回执恰一个生效");

        TankStatusResponse status = tankService.status(TankFixtures.STATION);
        assertEquals(0, status.reservedKg().compareTo(BigDecimal.ZERO.setScale(3)),
                "预占恰好结算一次，不重复返还");
        if ("RECEIPT_OK".equals(receiptResult)) {
            assertEquals(0, status.balanceKg().compareTo(new BigDecimal("30.000")),
                    "回执生效：余额扣20");
            assertEquals("EXECUTED", jdbc.queryForObject(
                    "SELECT status FROM injection_plan WHERE id = ?", String.class,
                    planned.planId()));
        } else {
            assertEquals(0, status.balanceKg().compareTo(new BigDecimal("50.000")),
                    "取消生效：余额不动");
            assertEquals("CANCELLED", jdbc.queryForObject(
                    "SELECT status FROM injection_plan WHERE id = ?", String.class,
                    planned.planId()));
        }
        // 释放类明细至多一条，CONSUME 至多一条
        assertTrue(count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'RELEASE'") <= 1);
        assertTrue(count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'CONSUME'") <= 1);
    }

    @Test
    void concurrentPlansNeverBreachSafetyReserve() throws Exception {
        TankFixtures.registerStandard(tankService);

        // 5 个 10kg 计划并发（版本冲突即读新版本重试）：恰 4 个预占成功，第 5 个被拦
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int attempt = 0; attempt < 20; attempt++) {
                    int version = tankService.status(TankFixtures.STATION).version();
                    try {
                        InjectionPlanResponse resp = planService.plan(new InjectionPlanRequest(
                                TankFixtures.STATION, TankFixtures.nextRequestId("PLAN"),
                                new BigDecimal("10"), TankFixtures.T, version));
                        return resp.status();
                    } catch (VersionConflictException conflict) {
                        // 版本冲突：读新版本重试
                    }
                }
                return "RETRY_EXHAUSTED";
            }));
        }
        start.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertEquals(4, results.stream().filter("RESERVED"::equals).count(),
                "恰4个计划预占成功（可用量恰等安全储备）");
        assertEquals(1, results.stream().filter("BLOCKED"::equals).count(),
                "第5个触及安全储备被拦并生成待补液事件");

        TankStatusResponse status = tankService.status(TankFixtures.STATION);
        assertEquals(0, status.reservedKg().compareTo(new BigDecimal("40.000")));
        assertEquals(0, status.availableKg().compareTo(new BigDecimal("10.000")),
                "并发预占不突破安全储备");
        assertEquals(1, status.pendingRefillEvents());
        assertEquals(4, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'RESERVE'"),
                "预占计量唯一");
    }
}
