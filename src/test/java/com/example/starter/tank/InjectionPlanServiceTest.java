package com.example.starter.tank;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.web.BusinessRejectedException;
import com.example.starter.web.ResourceNotFoundException;
import com.example.starter.web.dto.InjectionCancelRequest;
import com.example.starter.web.dto.InjectionCancelRequest;
import com.example.starter.web.dto.InjectionPlanRequest;
import com.example.starter.web.dto.InjectionPlanResponse;
import com.example.starter.web.dto.InjectionReceiptRequest;
import com.example.starter.web.dto.PlanActionResponse;
import com.example.starter.web.dto.TankRegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受控加注计划：预占安全储备边界（恰等允许）、回执释放未用、取消与重复回执边界、
 * 台账守恒勾稽。
 */
class InjectionPlanServiceTest extends AbstractIntegrationTest {

    private static final LocalDateTime T = LocalDate.of(2026, 9, 25).atTime(10, 0);

    @Autowired
    private TankService tankService;
    @Autowired
    private InjectionPlanService planService;

    @BeforeEach
    void registerTank() {
        // 容量100 / 安全储备10 / 初始余额50，登记后版本=1
        tankService.register(new TankRegisterRequest("ST01", "REG", new BigDecimal("100"),
                new BigDecimal("10"), new BigDecimal("50"), T));
    }

    private InjectionPlanResponse plan(String requestId, long expectedVersion, String requiredKg) {
        return planService.plan(new InjectionPlanRequest("ST01", requestId, expectedVersion,
                new BigDecimal(requiredKg), T.plusMinutes(1)));
    }

    private PlanActionResponse receipt(String requestId, long expectedVersion,
                                       String planRequestId, String actualKg) {
        return planService.receipt(new InjectionReceiptRequest("ST01", requestId, expectedVersion,
                planRequestId, new BigDecimal(actualKg), T.plusMinutes(2)));
    }

    @Test
    void planReservesAndKeepsAvailableAboveReserve() {
        InjectionPlanResponse resp = plan("PLAN-1", 1L, "20");
        assertTrue(resp.dispatched());
        assertEquals("PREOCCUPIED", resp.status());
        assertNull(resp.refillEventId());
        assertEquals(2L, resp.version());
        assertDecimalEquals("30", resp.availableAfterKg());
        assertDecimalEquals("20", decimal("SELECT reserved_kg FROM odorant_tank WHERE station_id = 1"));
    }

    @Test
    void planAllowsAvailableExactlyEqualToReserve() {
        // 预占后可用量恰等安全储备（50-40=10）：允许
        InjectionPlanResponse resp = plan("PLAN-2", 1L, "40");
        assertTrue(resp.dispatched());
        assertDecimalEquals("10", resp.availableAfterKg());
    }

    @Test
    void planBelowReserveCreatesRefillEventAndDoesNotDispatch() {
        // 预占后可用量 50-41=9 < 10：生成待补液事件且不下发，储罐计量与版本不变
        InjectionPlanResponse resp = plan("PLAN-3", 1L, "41");
        assertFalse(resp.dispatched());
        assertEquals("REJECTED", resp.status());
        assertNotNull(resp.refillEventId());
        assertEquals(1L, resp.version());
        assertDecimalEquals("50", resp.availableAfterKg());

        assertEquals(1, count("SELECT COUNT(*) FROM refill_event WHERE status = 'PENDING'"));
        // 缺口 = 需求41 + 安全储备10 − 可用50 = 1
        assertDecimalEquals("1", decimal("SELECT shortage_kg FROM refill_event"));
        assertDecimalEquals("41", decimal("SELECT required_kg FROM refill_event"));
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT status FROM injection_plan WHERE request_id = 'PLAN-3'", String.class));
        assertEquals(0, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'RESERVE'"));
        assertDecimalEquals("0", decimal("SELECT reserved_kg FROM odorant_tank WHERE station_id = 1"));
    }

    @Test
    void receiptConvertsPreoccupationAndReleasesUnused() {
        plan("PLAN-4", 1L, "20");
        PlanActionResponse resp = receipt("RCV-4", 2L, "PLAN-4", "15");
        assertEquals("EXECUTED", resp.status());
        assertDecimalEquals("15", resp.executedKg());
        assertDecimalEquals("5", resp.releasedKg());
        assertDecimalEquals("35", resp.balanceKg());
        assertDecimalEquals("0", resp.reservedKg());
        assertEquals(3L, resp.version());

        // 台账守恒：ΣΔ余额 = 50 − 15 = 35 = 当前余额；ΣΔ预占 = 0 = 当前预占
        assertDecimalEquals("35", decimal("SELECT SUM(delta_balance_kg) FROM tank_ledger"));
        assertDecimalEquals("0", decimal("SELECT SUM(delta_reserved_kg) FROM tank_ledger"));
    }

    @Test
    void receiptRejectsActualOverPreoccupied() {
        plan("PLAN-5", 1L, "20");
        assertThrows(BusinessRejectedException.class, () -> receipt("RCV-5", 2L, "PLAN-5", "20.001"));
        // 拒绝后计量不变
        assertDecimalEquals("50", decimal("SELECT balance_kg FROM odorant_tank WHERE station_id = 1"));
        assertDecimalEquals("20", decimal("SELECT reserved_kg FROM odorant_tank WHERE station_id = 1"));
    }

    @Test
    void duplicateReceiptRejectedWithoutDoubleDeduct() {
        plan("PLAN-6", 1L, "20");
        receipt("RCV-6", 2L, "PLAN-6", "15");
        // 不同 requestId 的重复回执：422 且不重复扣量
        assertThrows(BusinessRejectedException.class, () -> receipt("RCV-6B", 3L, "PLAN-6", "15"));
        assertDecimalEquals("35", decimal("SELECT balance_kg FROM odorant_tank WHERE station_id = 1"));
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'EXECUTE'"));
    }

    @Test
    void receiptSameKeySameParamsReplaysWithoutDoubleDeduct() {
        plan("PLAN-7", 1L, "20");
        PlanActionResponse first = receipt("RCV-7", 2L, "PLAN-7", "15");
        PlanActionResponse replay = receipt("RCV-7", 2L, "PLAN-7", "15");
        assertTrue(replay.idempotentReplay());
        assertFalse(first.idempotentReplay());
        assertDecimalEquals("35", decimal("SELECT balance_kg FROM odorant_tank WHERE station_id = 1"));
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'EXECUTE'"));
    }

    @Test
    void cancelReleasesOnlyUnexecutedPreoccupation() {
        plan("PLAN-8", 1L, "20");
        PlanActionResponse resp = planService.cancel(new InjectionCancelRequest(
                "ST01", "CXL-8", 2L, "PLAN-8", T.plusMinutes(3)));
        assertEquals("CANCELLED", resp.status());
        assertDecimalEquals("20", resp.releasedKg());
        assertDecimalEquals("50", resp.balanceKg());
        assertDecimalEquals("0", resp.reservedKg());
        assertDecimalEquals("0", decimal("SELECT SUM(delta_reserved_kg) FROM tank_ledger"));
    }

    @Test
    void cancelExecutedPlanRejected() {
        plan("PLAN-9", 1L, "20");
        receipt("RCV-9", 2L, "PLAN-9", "20");
        assertThrows(BusinessRejectedException.class, () -> planService.cancel(
                new InjectionCancelRequest("ST01", "CXL-9", 3L, "PLAN-9", T.plusMinutes(3))));
    }

    @Test
    void receiptOnRejectedPlanRejected() {
        plan("PLAN-10", 1L, "41");
        assertThrows(BusinessRejectedException.class, () -> receipt("RCV-10", 1L, "PLAN-10", "1"));
    }

    @Test
    void planOnUnboundStationReturns404() {
        assertThrows(ResourceNotFoundException.class, () -> planService.plan(
                new InjectionPlanRequest("ST02", "PLAN-11", 1L, new BigDecimal("1"), T)));
    }

    @Test
    void receiptOnUnknownPlanReturns404() {
        assertThrows(ResourceNotFoundException.class, () -> receipt("RCV-11", 1L, "NO-SUCH-PLAN", "1"));
    }
}
