package com.example.starter.tank;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.web.VersionConflictException;
import com.example.starter.web.dto.InjectionCancelRequest;
import com.example.starter.web.dto.InjectionPlanRequest;
import com.example.starter.web.dto.InjectionPlanResponse;
import com.example.starter.web.dto.InjectionReceiptRequest;
import com.example.starter.web.dto.PlanActionResponse;
import com.example.starter.web.dto.RefillRequest;
import com.example.starter.web.dto.TankRegisterRequest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 储罐并发边界：预占不突破安全储备、取消与回执唯一计量、补液与消耗并发不超罐不重复返还。
 * 版本冲突（409）由客户端以最新版本重试，模拟乐观锁下的真实并发协商。
 */
class TankConcurrencyTest extends AbstractIntegrationTest {

    private static final LocalDateTime T = LocalDate.of(2026, 9, 25).atTime(10, 0);

    @Autowired
    private TankService tankService;
    @Autowired
    private InjectionPlanService planService;
    @Autowired
    private RefillService refillService;

    private void register(String capacity, String reserve, String balance) {
        tankService.register(new TankRegisterRequest("ST01", "REG", new BigDecimal(capacity),
                new BigDecimal(reserve), new BigDecimal(balance), T));
    }

    private long currentVersion() {
        return tankService.status("ST01").version();
    }

    @Test
    void concurrentPlansNeverReserveBelowSafetyReserve() throws Exception {
        // 可用50、安全储备10：恰4个10kg计划可预占（余量恰等10允许），其余生成待补液事件
        register("1000", "10", "50");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        @SuppressWarnings("unchecked")
        Future<InjectionPlanResponse>[] futures = new Future[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures[i] = pool.submit(() -> {
                start.await();
                for (int attempt = 0; attempt < 40; attempt++) {
                    try {
                        return planService.plan(new InjectionPlanRequest("ST01", "PC-" + idx,
                                currentVersion(), new BigDecimal("10"), T.plusMinutes(1)));
                    } catch (VersionConflictException conflict) {
                        // 版本冲突：以最新版本重试
                    }
                }
                throw new IllegalStateException("plan not accepted after retries");
            });
        }
        start.countDown();

        int dispatched = 0;
        int rejected = 0;
        for (Future<InjectionPlanResponse> f : futures) {
            InjectionPlanResponse resp = f.get(30, TimeUnit.SECONDS);
            if (resp.dispatched()) {
                dispatched++;
            } else {
                rejected++;
            }
        }
        pool.shutdown();

        assertEquals(4, dispatched, "恰4个计划可预占（余量不低于安全储备）");
        assertEquals(2, rejected, "其余计划生成待补液事件且不下发");
        assertDecimalEquals("40", decimal("SELECT reserved_kg FROM odorant_tank WHERE station_id = 1"));
        assertDecimalEquals("10", decimal(
                "SELECT balance_kg - reserved_kg FROM odorant_tank WHERE station_id = 1"));
        assertEquals(2, count("SELECT COUNT(*) FROM refill_event WHERE status = 'PENDING'"));
        // 台账守恒：ΣΔ预占 = 当前预占
        assertDecimalEquals("40", decimal("SELECT SUM(delta_reserved_kg) FROM tank_ledger"));
    }

    @Test
    void concurrentCancelAndReceiptMeterExactlyOnce() throws Exception {
        register("100", "10", "50");
        planService.plan(new InjectionPlanRequest("ST01", "PLAN-X", 1L,
                new BigDecimal("20"), T.plusMinutes(1)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Object> receiptFuture = pool.submit(() -> {
            start.await();
            return planService.receipt(new InjectionReceiptRequest("ST01", "RCV-X", 2L,
                    "PLAN-X", new BigDecimal("12"), T.plusMinutes(2)));
        });
        Future<Object> cancelFuture = pool.submit(() -> {
            start.await();
            return planService.cancel(new InjectionCancelRequest("ST01", "CXL-X", 2L,
                    "PLAN-X", T.plusMinutes(2)));
        });
        start.countDown();

        int succeeded = 0;
        for (Future<Object> f : List.of(receiptFuture, cancelFuture)) {
            try {
                f.get(30, TimeUnit.SECONDS);
                succeeded++;
            } catch (java.util.concurrent.ExecutionException e) {
                assertTrue(e.getCause() instanceof VersionConflictException
                                || e.getCause() instanceof com.example.starter.web.BusinessRejectedException,
                        "并发败方仅允许版本冲突或状态拒绝，实际: " + e.getCause());
            }
        }
        pool.shutdown();

        assertEquals(1, succeeded, "取消与回执并发：恰一个生效");
        String status = jdbc.queryForObject(
                "SELECT status FROM injection_plan WHERE request_id = 'PLAN-X'", String.class);
        assertDecimalEquals("0", decimal("SELECT reserved_kg FROM odorant_tank WHERE station_id = 1"));
        if ("EXECUTED".equals(status)) {
            assertDecimalEquals("38", decimal("SELECT balance_kg FROM odorant_tank WHERE station_id = 1"));
        } else {
            assertEquals("CANCELLED", status);
            assertDecimalEquals("50", decimal("SELECT balance_kg FROM odorant_tank WHERE station_id = 1"));
        }
        // 守恒：预占变动合计归零；余额变动合计（含 REGISTER 初始入账）= 当前余额
        assertDecimalEquals("0", decimal("SELECT SUM(delta_reserved_kg) FROM tank_ledger"));
        assertDecimalEquals(
                decimal("SELECT balance_kg FROM odorant_tank WHERE station_id = 1").toPlainString(),
                decimal("SELECT SUM(delta_balance_kg) FROM tank_ledger"));
    }

    @Test
    void concurrentRefillAndReceiptKeepUniqueMetering() throws Exception {
        register("100", "10", "50");
        planService.plan(new InjectionPlanRequest("ST01", "PLAN-Y", 1L,
                new BigDecimal("20"), T.plusMinutes(1)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<PlanActionResponse> receiptFuture = pool.submit(() -> {
            start.await();
            for (int attempt = 0; attempt < 40; attempt++) {
                try {
                    return planService.receipt(new InjectionReceiptRequest("ST01", "RCV-Y",
                            currentVersion(), "PLAN-Y", new BigDecimal("12"), T.plusMinutes(2)));
                } catch (VersionConflictException conflict) {
                    // 版本冲突：以最新版本重试
                }
            }
            throw new IllegalStateException("receipt not accepted after retries");
        });
        Future<Object> refillFuture = pool.submit(() -> {
            start.await();
            for (int attempt = 0; attempt < 40; attempt++) {
                try {
                    return refillService.confirm(new RefillRequest("ST01", "REF-Y",
                            currentVersion(), "BATCH-Y", new BigDecimal("30"),
                            new BigDecimal("100.000"), List.of("OP-A", "OP-B"), T.plusMinutes(3)));
                } catch (VersionConflictException conflict) {
                    // 版本冲突：以最新版本重试
                }
            }
            throw new IllegalStateException("refill not accepted after retries");
        });
        start.countDown();

        receiptFuture.get(30, TimeUnit.SECONDS);
        refillFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 唯一计量：余额 = 50 − 12 + 30 = 68，预占归零，不超罐也不重复返还
        assertDecimalEquals("68", decimal("SELECT balance_kg FROM odorant_tank WHERE station_id = 1"));
        assertDecimalEquals("0", decimal("SELECT reserved_kg FROM odorant_tank WHERE station_id = 1"));
        assertEquals(4L, (long) jdbc.queryForObject(
                "SELECT version FROM odorant_tank WHERE station_id = 1", Long.class));
        assertEquals(1, count("SELECT COUNT(*) FROM refill_order WHERE transport_batch_no = 'BATCH-Y'"));
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'EXECUTE'"));
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'REFILL'"));
        // 守恒：余额变动合计（含 REGISTER 初始入账50）= 当前余额68
        assertDecimalEquals("68", decimal("SELECT SUM(delta_balance_kg) FROM tank_ledger"));
        assertDecimalEquals("0", decimal("SELECT SUM(delta_reserved_kg) FROM tank_ledger"));
    }
}
