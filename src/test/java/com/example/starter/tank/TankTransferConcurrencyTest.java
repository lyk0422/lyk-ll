package com.example.starter.tank;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.web.VersionConflictException;
import com.example.starter.web.dto.TankPlanRequest;
import com.example.starter.web.dto.TankPlanResponse;
import com.example.starter.web.dto.TankRefillRequest;
import com.example.starter.web.dto.TankRegisterRequest;
import com.example.starter.web.dto.TankTransferApplyRequest;
import com.example.starter.web.dto.TankTransferApplyResponse;
import com.example.starter.web.dto.TankTransferCancelRequest;
import com.example.starter.web.dto.TankTransferConfirmRequest;
import com.example.starter.web.dto.TankTransferRecomputeView;
import com.example.starter.web.dto.TankView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
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
 * 跨罐调拨并发边界：调拨申请、原加注计划与补液共享源安全储备与目标容量约束；
 * 撤销与最后确认竞争只能全撤销或全调拨；全部断言最终数据而非打印。
 */
class TankTransferConcurrencyTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDate.of(2026, 9, 26).atTime(10, 0);
    private static final Instant APPLY_AT = Instant.parse("2026-09-26T02:00:00Z");
    private static final Instant CONFIRM_AT = Instant.parse("2026-09-26T03:00:00Z");
    private static final Instant CANCEL_AT = Instant.parse("2026-09-26T03:00:00Z");

    @Autowired
    private TankService tankService;
    @Autowired
    private TankTransferService transferService;

    private void register(String station, String tankCode, String variety,
                          String capacity, String safety, String balance) {
        tankService.register(new TankRegisterRequest(station, tankCode, "REG-" + tankCode,
                new BigDecimal(capacity), new BigDecimal(safety), new BigDecimal(balance),
                T0, variety));
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

    private BigDecimal sumLedger(String column, long tankId) {
        BigDecimal value = jdbc.queryForObject(
                "SELECT COALESCE(SUM(" + column + "), 0) FROM tank_ledger WHERE tank_id = ?",
                BigDecimal.class, tankId);
        return value == null ? BigDecimal.ZERO : value;
    }

    @Test
    void concurrentTransferApplyAndPlanShareSourceConstraint() throws Exception {
        // 源罐余额 500、安全储备 100：调拨 250 与计划 250 并发，恰一侧预占成功
        register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        register("ST02", "TK02", "VAR-TBT", "1000", "50", "0");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<String> transferFuture = pool.submit(() -> {
            start.await();
            try {
                TankTransferApplyResponse r = transferService.apply(new TankTransferApplyRequest(
                        "TK01", "TK02", "VAR-TBT", "TR-1", new BigDecimal("250"), "TB-1",
                        List.of("OP-A", "OP-B"), APPLY_AT));
                return "TRANSFER:" + r.status();
            } catch (Exception e) {
                return "TRANSFER:" + e.getClass().getSimpleName();
            }
        });
        Future<TankPlanResponse> planFuture = pool.submit(() -> {
            start.await();
            return retryOnVersionConflict(() -> tankService.createPlan(new TankPlanRequest(
                    "TK01", "PL-1", currentVersion("TK01"), T0, new BigDecimal("250"))), 5);
        });
        start.countDown();
        String transferResult = transferFuture.get(15, TimeUnit.SECONDS);
        TankPlanResponse plan = planFuture.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        TankView source = tankService.tankView("TK01");
        // 两侧共享源约束：500 − 250 − 250 = 0 低于安全储备，只允许一侧预占 250
        assertBd("250", source.reservedKg(), "调拨与计划并发只允许一侧预占");
        assertBd("250", source.availableKg());
        assertTrue(source.availableKg().compareTo(source.safetyReserveKg()) >= 0,
                "并发后可用量不得低于安全储备");
        if ("TRANSFER:RESERVED".equals(transferResult)) {
            assertEquals("BLOCKED", plan.status(), "调拨先占后计划须被拦截");
        } else {
            assertEquals("TRANSFER:BusinessRejectedException", transferResult,
                    "计划先占后调拨须业务拒绝");
            assertEquals("RESERVED", plan.status());
        }
        assertBd("250", sumLedger("reserved_delta_kg", source.tankId()), "预占须与台账勾稽");
    }

    @Test
    void concurrentTransferApplyAndRefillShareTargetSpace() throws Exception {
        // 目标罐容量 300、余额 100：调拨入 150 与补液 150 并发，恰一侧成功（100+150+150>300）
        register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        register("ST02", "TK02", "VAR-TBT", "300", "50", "100");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<String> transferFuture = pool.submit(() -> {
            start.await();
            try {
                TankTransferApplyResponse r = transferService.apply(new TankTransferApplyRequest(
                        "TK01", "TK02", "VAR-TBT", "TR-1", new BigDecimal("150"), "TB-1",
                        List.of("OP-A", "OP-B"), APPLY_AT));
                return "TRANSFER:" + r.status();
            } catch (Exception e) {
                return "TRANSFER:" + e.getClass().getSimpleName();
            }
        });
        Future<String> refillFuture = pool.submit(() -> {
            start.await();
            try {
                retryOnVersionConflict(() -> tankService.refill(new TankRefillRequest(
                        "TK02", "RF-1", currentVersion("TK02"), "RB-1", new BigDecimal("150"),
                        new BigDecimal("20.000"), List.of("OP-A", "OP-B"), T0)), 5);
                return "REFILL:OK";
            } catch (Exception e) {
                return "REFILL:" + e.getClass().getSimpleName();
            }
        });
        start.countDown();
        String transferResult = transferFuture.get(15, TimeUnit.SECONDS);
        String refillResult = refillFuture.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        // 补液不得占用留给调拨的目标空间：恰一侧成功
        assertTrue(("TRANSFER:RESERVED".equals(transferResult) && "REFILL:BusinessRejectedException".equals(refillResult))
                        || ("TRANSFER:BusinessRejectedException".equals(transferResult) && "REFILL:OK".equals(refillResult)),
                "调拨与补液并发须恰一侧成功：transfer=" + transferResult + " refill=" + refillResult);
        TankView target = tankService.tankView("TK02");
        BigDecimal occupied = target.balanceKg().add(target.incomingReservedKg());
        assertTrue(occupied.compareTo(target.capacityKg()) <= 0,
                "余额+接收预占不得超容量: " + occupied);
        assertBd("250", occupied, "恰一侧占得 150 空间");
    }

    @Test
    void cancelAndConfirmRaceSettlesExactlyOneTerminalState() throws Exception {
        register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        register("ST02", "TK02", "VAR-TBT", "800", "50", "100");
        TankTransferApplyResponse applied = transferService.apply(new TankTransferApplyRequest(
                "TK01", "TK02", "VAR-TBT", "TR-1", new BigDecimal("120"), "TB-1",
                List.of("OP-A", "OP-B"), APPLY_AT));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<String> confirmFuture = pool.submit(() -> {
            start.await();
            try {
                var r = transferService.confirm(new TankTransferConfirmRequest(
                        applied.transferId(), "TC-1", 1L, new BigDecimal("120"), "CRED-1",
                        CONFIRM_AT));
                return "CONFIRM:" + r.status();
            } catch (Exception e) {
                return "CONFIRM:" + e.getClass().getSimpleName();
            }
        });
        Future<String> cancelFuture = pool.submit(() -> {
            start.await();
            try {
                var r = transferService.cancel(new TankTransferCancelRequest(
                        applied.transferId(), "TX-1", 1L, CANCEL_AT));
                return "CANCEL:" + r.status();
            } catch (Exception e) {
                return "CANCEL:" + e.getClass().getSimpleName();
            }
        });
        start.countDown();
        String confirmResult = confirmFuture.get(15, TimeUnit.SECONDS);
        String cancelResult = cancelFuture.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        boolean confirmWon = "CONFIRM:EXECUTED".equals(confirmResult);
        boolean cancelWon = "CANCEL:CANCELLED".equals(cancelResult);
        assertTrue(confirmWon ^ cancelWon,
                "撤销与最后确认竞争只能全撤销或全调拨：confirm=" + confirmResult
                        + " cancel=" + cancelResult);

        TankView source = tankService.tankView("TK01");
        TankView target = tankService.tankView("TK02");
        assertBd("0", source.reservedKg(), "源预占只能释放/消耗一次");
        assertBd("0", target.incomingReservedKg(), "目标接收预占只能释放/入账一次");
        if (confirmWon) {
            assertEquals("CANCEL:BusinessRejectedException", cancelResult, "已执行不得撤回");
            assertBd("380", source.balanceKg());
            assertBd("220", target.balanceKg());
        } else {
            assertEquals("CONFIRM:BusinessRejectedException", confirmResult, "已撤销不得确认");
            assertBd("500", source.balanceKg());
            assertBd("100", target.balanceKg());
        }
        // 守恒：台账勾稽与两罐总量
        assertBd("0", sumLedger("reserved_delta_kg", source.tankId()));
        assertBd("0", sumLedger("incoming_reserved_delta_kg", target.tankId()));
        assertBd("600", source.balanceKg().add(target.balanceKg()), "两罐总量恒为 600");
        assertEquals(source.balanceKg(), sumLedger("balance_delta_kg", source.tankId()));
        assertEquals(target.balanceKg(), sumLedger("balance_delta_kg", target.tankId()));

        // 从调拨单复算：双侧净变动与流量
        TankTransferRecomputeView recompute = transferService.recompute(applied.transferId());
        assertBd("0", recompute.netBalanceDeltaKg());
        assertBd("0", recompute.sourceReservedDeltaKg());
        assertBd("0", recompute.targetIncomingReservedDeltaKg());
        assertBd(confirmWon ? "120" : "0", recompute.transferredKg());
    }

    @Test
    void concurrentAppliesNeverBreachSafetyReserve() throws Exception {
        // 源罐余额 500、安全储备 100：两笔 300 调拨并发，恰一笔预占成功
        register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        register("ST02", "TK02", "VAR-TBT", "1000", "50", "0");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<String> f1 = pool.submit(() -> {
            start.await();
            try {
                transferService.apply(new TankTransferApplyRequest("TK01", "TK02", "VAR-TBT",
                        "TR-1", new BigDecimal("300"), "TB-1", List.of("OP-A", "OP-B"), APPLY_AT));
                return "OK";
            } catch (Exception e) {
                return e.getClass().getSimpleName();
            }
        });
        Future<String> f2 = pool.submit(() -> {
            start.await();
            try {
                transferService.apply(new TankTransferApplyRequest("TK01", "TK02", "VAR-TBT",
                        "TR-2", new BigDecimal("300"), "TB-2", List.of("OP-A", "OP-B"), APPLY_AT));
                return "OK";
            } catch (Exception e) {
                return e.getClass().getSimpleName();
            }
        });
        start.countDown();
        String r1 = f1.get(15, TimeUnit.SECONDS);
        String r2 = f2.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        int succeeded = ("OK".equals(r1) ? 1 : 0) + ("OK".equals(r2) ? 1 : 0);
        assertEquals(1, succeeded, "两笔 300 并发只允许一笔预占：r1=" + r1 + " r2=" + r2);
        TankView source = tankService.tankView("TK01");
        assertBd("300", source.reservedKg());
        assertBd("200", source.availableKg());
        assertTrue(source.availableKg().compareTo(source.safetyReserveKg()) >= 0);
        assertEquals(1, count("SELECT COUNT(*) FROM tank_transfer"));
        assertBd("300", tankService.tankView("TK02").incomingReservedKg());
    }
}
