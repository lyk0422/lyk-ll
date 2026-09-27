package com.example.starter.tank;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.web.VersionConflictException;
import com.example.starter.web.dto.TankCancelRequest;
import com.example.starter.web.dto.TankPlanRequest;
import com.example.starter.web.dto.TankPlanResponse;
import com.example.starter.web.dto.TankReceiptRequest;
import com.example.starter.web.dto.TankReceiptResponse;
import com.example.starter.web.dto.TankRefillRequest;
import com.example.starter.web.dto.TankRefillResponse;
import com.example.starter.web.dto.TankRegisterRequest;
import com.example.starter.web.dto.TankView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 储罐并发边界：计划/回执/取消/补液在储罐行锁与版本乐观锁下串行裁决，
 * 保持唯一计量——不超罐、不低于安全储备、不重复返还、不重复扣量。
 */
class TankConcurrencyTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDate.of(2026, 9, 26).atTime(10, 0);

    @Autowired
    private TankService tankService;

    private void register(String tankCode, String capacity, String safety, String balance) {
        tankService.register(new TankRegisterRequest("ST01", tankCode, "REG-" + tankCode,
                new BigDecimal(capacity), new BigDecimal(safety), new BigDecimal(balance), T0));
    }

    /**
     * 版本冲突时刷新版本重试（客户端乐观锁协议），返回最终结果。
     */
    private <T> T retryOnVersionConflict(Supplier<T> action, int maxAttempts) {
        for (int i = 0; i < maxAttempts; i++) {
            try {
                return action.get();
            } catch (VersionConflictException conflict) {
                // 过期版本：刷新后重试
            }
        }
        return action.get();
    }

    private long currentVersion(String tankCode) {
        return tankService.tankView(tankCode).version();
    }

    private void assertBd(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
    }

    private void assertBd(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                message + ": expected " + expected + " but was " + actual);
    }

    @Test
    void concurrentPlansNeverBreachSafetyReserve() throws Exception {
        // 余额 500、安全储备 100：两笔 250 的计划并发，恰一笔预占成功，另一笔重试后须被拦截
        register("TK01", "1000", "100", "500");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<TankPlanResponse> f1 = pool.submit(() -> {
            start.await();
            return retryOnVersionConflict(() -> tankService.createPlan(new TankPlanRequest(
                    "TK01", "PL-A", currentVersion("TK01"), T0, new BigDecimal("250"))), 5);
        });
        Future<TankPlanResponse> f2 = pool.submit(() -> {
            start.await();
            return retryOnVersionConflict(() -> tankService.createPlan(new TankPlanRequest(
                    "TK01", "PL-B", currentVersion("TK01"), T0, new BigDecimal("250"))), 5);
        });
        start.countDown();
        TankPlanResponse r1 = f1.get(15, TimeUnit.SECONDS);
        TankPlanResponse r2 = f2.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        int reserved = ("RESERVED".equals(r1.status()) ? 1 : 0) + ("RESERVED".equals(r2.status()) ? 1 : 0);
        int blocked = ("BLOCKED".equals(r1.status()) ? 1 : 0) + ("BLOCKED".equals(r2.status()) ? 1 : 0);
        assertEquals(1, reserved, "两笔 250 并发只允许一笔预占（500−250−250=0 低于安全储备）");
        assertEquals(1, blocked, "另一笔须被拦截并生成待补液事件");

        TankView view = tankService.tankView("TK01");
        assertBd("250", view.reservedKg());
        assertBd("250", view.availableKg());
        assertTrue(view.availableKg().compareTo(view.safetyReserveKg()) >= 0,
                "并发预占后可用量不得低于安全储备");
        assertEquals(1, count("SELECT COUNT(*) FROM tank_refill_event WHERE status = 'OPEN'"));
        // 守恒：预占分录合计 = 当前预占
        assertBd("250", sumReservedDelta(view.tankId()));
    }

    @Test
    void concurrentReceiptAndCancelMeterExactlyOnce() throws Exception {
        register("TK01", "1000", "100", "500");
        TankPlanResponse plan = tankService.createPlan(
                new TankPlanRequest("TK01", "PL-1", 1L, T0, new BigDecimal("300")));
        assertEquals("RESERVED", plan.status());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        // 回执与取消并发：同一预占只允许一种终态，唯一计量
        Future<String> receiptFuture = pool.submit(() -> {
            start.await();
            try {
                TankReceiptResponse r = retryOnVersionConflict(() -> tankService.receipt(
                        new TankReceiptRequest(plan.planId(), "RC-1", currentVersion("TK01"),
                                new BigDecimal("300"), T0.plusHours(1))), 5);
                return "RECEIPT:" + r.status();
            } catch (Exception e) {
                return "RECEIPT:" + e.getClass().getSimpleName();
            }
        });
        Future<String> cancelFuture = pool.submit(() -> {
            start.await();
            try {
                var c = retryOnVersionConflict(() -> tankService.cancel(
                        new TankCancelRequest(plan.planId(), "CN-1", currentVersion("TK01"),
                                T0.plusHours(2))), 5);
                return "CANCEL:" + c.status();
            } catch (Exception e) {
                return "CANCEL:" + e.getClass().getSimpleName();
            }
        });
        start.countDown();
        String receiptResult = receiptFuture.get(15, TimeUnit.SECONDS);
        String cancelResult = cancelFuture.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        TankView view = tankService.tankView("TK01");
        boolean receiptWon = "RECEIPT:EXECUTED".equals(receiptResult);
        boolean cancelWon = "CANCEL:CANCELLED".equals(cancelResult);
        assertTrue(receiptWon ^ cancelWon,
                "回执与取消并发须恰一个生效：receipt=" + receiptResult + " cancel=" + cancelResult);
        assertBd("0", view.reservedKg(), "预占只能释放/消耗一次");
        if (receiptWon) {
            assertBd("200", view.balanceKg(), "回执生效：余额扣 300");
            assertEquals("CANCEL:BusinessRejectedException", cancelResult,
                    "已执行计划取消须业务拒绝");
        } else {
            assertBd("500", view.balanceKg(), "取消生效：余额不动");
            assertEquals("RECEIPT:BusinessRejectedException", receiptResult,
                    "已取消计划回执须业务拒绝");
        }
        // 守恒：台账分录勾稽
        assertBd("0", sumReservedDelta(view.tankId()));
        assertEquals(view.balanceKg(), sumBalanceDelta(view.tankId()));
    }

    @Test
    void concurrentRefillsNoLostUpdateNoOverCapacity() throws Exception {
        register("TK01", "1000", "100", "100");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Future<?>[] futures = new Future<?>[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures[i] = pool.submit(() -> {
                start.await();
                return retryOnVersionConflict(() -> tankService.refill(new TankRefillRequest(
                        "TK01", "RF-" + idx, currentVersion("TK01"), "BATCH-" + idx,
                        new BigDecimal("50"), new BigDecimal("20.000"),
                        List.of("OP-A", "OP-B"), T0)), 10);
            });
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();

        TankView view = tankService.tankView("TK01");
        assertBd("300", view.balanceKg(), "4 笔 50 并发补液不得丢失更新");
        assertEquals(5L, view.version(), "登记 v1 + 4 次成功补液");
        assertEquals(4, count("SELECT COUNT(*) FROM tank_refill"));
        assertEquals(4, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'REFILL'"));
        assertTrue(view.balanceKg().compareTo(view.capacityKg()) <= 0, "不得超罐");
        assertEquals(view.balanceKg(), sumBalanceDelta(view.tankId()), "余额须与台账勾稽");
    }

    @Test
    void concurrentRefillAndPlanKeepInvariants() throws Exception {
        // 余额 150、安全储备 100：计划 75 与补液 50 并发。
        // 计划先提交：150−75=75<100 被拦截并生成事件，补液后 200−75=125≥100 事件被关闭；
        // 补液先提交：200−75=125≥100 计划预占成功。两种顺序均不得违反安全储备与守恒。
        register("TK01", "1000", "100", "150");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<TankPlanResponse> planFuture = pool.submit(() -> {
            start.await();
            return retryOnVersionConflict(() -> tankService.createPlan(new TankPlanRequest(
                    "TK01", "PL-1", currentVersion("TK01"), T0, new BigDecimal("75"))), 5);
        });
        Future<TankRefillResponse> refillFuture = pool.submit(() -> {
            start.await();
            return retryOnVersionConflict(() -> tankService.refill(new TankRefillRequest(
                    "TK01", "RF-1", currentVersion("TK01"), "BATCH-1", new BigDecimal("50"),
                    new BigDecimal("20.000"), List.of("OP-A", "OP-B"), T0)), 5);
        });
        start.countDown();
        TankPlanResponse plan = planFuture.get(15, TimeUnit.SECONDS);
        refillFuture.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        TankView view = tankService.tankView("TK01");
        assertBd("200", view.balanceKg());
        assertEquals(3L, view.version(), "登记+计划+补液恰三次成功变更");
        assertTrue(view.availableKg().compareTo(view.safetyReserveKg()) >= 0,
                "并发后可用量不得低于安全储备");
        if ("BLOCKED".equals(plan.status())) {
            assertBd("0", view.reservedKg());
            assertEquals(0, count("SELECT COUNT(*) FROM tank_refill_event WHERE status = 'OPEN'"),
                    "补液在后须关闭可满足事件");
        } else {
            assertEquals("RESERVED", plan.status());
            assertBd("75", view.reservedKg());
        }
        assertEquals(view.balanceKg(), sumBalanceDelta(view.tankId()), "余额须与台账勾稽");
        assertEquals(view.reservedKg(), sumReservedDelta(view.tankId()), "预占须与台账勾稽");
    }

    @Test
    void concurrentSameRequestIdRefillExecutesOnce() throws Exception {
        register("TK01", "1000", "100", "100");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Future<TankRefillResponse>[] futures = new Future[threads];
        for (int i = 0; i < threads; i++) {
            futures[i] = pool.submit(() -> {
                start.await();
                return tankService.refill(new TankRefillRequest("TK01", "RF-SAME", 1L, "BATCH-1",
                        new BigDecimal("50"), new BigDecimal("20.000"),
                        List.of("OP-A", "OP-B"), T0));
            });
        }
        start.countDown();
        int firstCount = 0;
        int replayCount = 0;
        Long refillId = null;
        for (Future<TankRefillResponse> f : futures) {
            TankRefillResponse resp = f.get(20, TimeUnit.SECONDS);
            if (resp.idempotentReplay()) {
                replayCount++;
            } else {
                firstCount++;
                refillId = resp.refillId();
            }
        }
        pool.shutdown();

        assertEquals(1, firstCount, "同键同参并发只允许一个请求执行入账");
        assertEquals(threads - 1, replayCount);
        for (Future<TankRefillResponse> f : futures) {
            assertEquals(refillId, f.get(5, TimeUnit.SECONDS).refillId(), "重放须返回首次补液单");
        }
        assertBd("150", tankService.tankView("TK01").balanceKg());
        assertEquals(1, count("SELECT COUNT(*) FROM tank_refill"), "并发同键不得重复入账");
    }

    private BigDecimal sumBalanceDelta(long tankId) {
        BigDecimal v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(balance_delta_kg),0) FROM tank_ledger WHERE tank_id = ?",
                BigDecimal.class, tankId);
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal sumReservedDelta(long tankId) {
        BigDecimal v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(reserved_delta_kg),0) FROM tank_ledger WHERE tank_id = ?",
                BigDecimal.class, tankId);
        return v == null ? BigDecimal.ZERO : v;
    }
}
