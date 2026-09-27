package com.example.starter.tank;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.web.BusinessRejectedException;
import com.example.starter.web.IdempotentConflictException;
import com.example.starter.web.ResourceNotFoundException;
import com.example.starter.web.VersionConflictException;
import com.example.starter.web.dto.InjectionCancelRequest;
import com.example.starter.web.dto.InjectionCancelResponse;
import com.example.starter.web.dto.InjectionPlanRequest;
import com.example.starter.web.dto.InjectionPlanResponse;
import com.example.starter.web.dto.InjectionReceiptRequest;
import com.example.starter.web.dto.InjectionReceiptResponse;
import com.example.starter.web.dto.TankRegisterResponse;
import com.example.starter.web.dto.TankStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受控加注计划：预占（恰等安全储备允许）、不足生成待补液事件且不下发、
 * 回执转换与释放、取消只释放未执行预占、版本与幂等边界。
 */
class InjectionPlanServiceTest extends AbstractIntegrationTest {

    @Autowired
    private TankService tankService;
    @Autowired
    private InjectionPlanService planService;

    private InjectionPlanResponse plan(BigDecimal plannedKg, int expectedVersion) {
        return planService.plan(new InjectionPlanRequest(TankFixtures.STATION,
                TankFixtures.nextRequestId("PLAN"), plannedKg, TankFixtures.T, expectedVersion));
    }

    @Test
    void planReservesAndBumpsVersion() {
        TankFixtures.registerStandard(tankService);

        InjectionPlanResponse resp = plan(new BigDecimal("30"), 1);
        assertEquals("RESERVED", resp.status());
        assertTrue(resp.dispatched());
        assertEquals(2, resp.version(), "成功变更版本加1");
        assertEquals(0, resp.reservedKg().compareTo(new BigDecimal("30.000")));
        assertEquals(0, resp.availableKg().compareTo(new BigDecimal("20.000")));

        TankStatusResponse status = tankService.status(TankFixtures.STATION);
        assertEquals(2, status.version());
        assertEquals(0, status.balanceKg().compareTo(new BigDecimal("50.000")), "预占不减余额");
    }

    @Test
    void planExactlyAtSafetyReserveIsAllowed() {
        TankFixtures.registerStandard(tankService);

        // 预占40后可用量恰等安全储备10：允许
        InjectionPlanResponse resp = plan(new BigDecimal("40"), 1);
        assertEquals("RESERVED", resp.status());
        assertTrue(resp.dispatched());
        assertEquals(0, resp.availableKg().compareTo(new BigDecimal("10.000")));
    }

    @Test
    void planBelowSafetyReserveIsBlockedAndRaisesRefillEvent() {
        TankFixtures.registerStandard(tankService);

        // 预占41后可用量9低于安全储备10：生成待补液事件且不下发加注
        InjectionPlanResponse resp = plan(new BigDecimal("41"), 1);
        assertEquals("BLOCKED", resp.status());
        assertFalse(resp.dispatched(), "不得伪报加注成功");
        assertEquals(1, resp.version(), "未发生储罐变更，版本不变");
        assertEquals(0, resp.reservedKg().compareTo(BigDecimal.ZERO.setScale(3)),
                "BLOCKED 不预占");
        Long eventId = resp.refillEventId();
        assertTrue(eventId != null);

        List<RefillEvent> events = tankService.refillEvents(TankFixtures.STATION);
        assertEquals(1, events.size());
        assertEquals("PENDING", events.get(0).status());
        assertEquals("PLAN", events.get(0).source());
        assertEquals(0, events.get(0).deficitKg().compareTo(new BigDecimal("1.000")),
                "缺口=安全储备10-(可用50-预占41)");
    }

    @Test
    void receiptConsumesActualAndReleaseUnused() {
        TankFixtures.registerStandard(tankService);
        InjectionPlanResponse planned = plan(new BigDecimal("30"), 1);

        InjectionReceiptResponse receipt = planService.receipt(new InjectionReceiptRequest(
                TankFixtures.nextRequestId("RCPT"), planned.planId(),
                new BigDecimal("25"), TankFixtures.T.plusHours(1), 2));

        assertEquals("EXECUTED", receipt.status());
        assertEquals(0, receipt.releasedKg().compareTo(new BigDecimal("5.000")));
        assertEquals(0, receipt.balanceKg().compareTo(new BigDecimal("25.000")));
        assertEquals(0, receipt.reservedKg().compareTo(BigDecimal.ZERO.setScale(3)));
        assertEquals(3, receipt.version());

        // 台账守恒：REGISTER50 + CONSUME25 + RELEASE5
        List<TankLedgerEntry> ledger = tankService.ledger(TankFixtures.STATION);
        assertEquals(4, ledger.size());
        assertEquals("CONSUME", ledger.get(2).entryType());
        assertEquals(0, ledger.get(2).balanceAfterKg().compareTo(new BigDecimal("25.000")));
        assertEquals(0, ledger.get(2).reservedAfterKg().compareTo(new BigDecimal("5.000")));
        assertEquals("RELEASE", ledger.get(3).entryType());
        assertEquals(0, ledger.get(3).reservedAfterKg().compareTo(BigDecimal.ZERO.setScale(3)));
    }

    @Test
    void receiptActualAbovePlannedIsRejectedAndDoesNotOccupyKey() {
        TankFixtures.registerStandard(tankService);
        InjectionPlanResponse planned = plan(new BigDecimal("30"), 1);

        String requestId = TankFixtures.nextRequestId("RCPT");
        InjectionReceiptRequest tooMuch = new InjectionReceiptRequest(requestId,
                planned.planId(), new BigDecimal("30.001"), TankFixtures.T.plusHours(1), 2);
        assertThrows(BusinessRejectedException.class, () -> planService.receipt(tooMuch));
        assertEquals(0, count("SELECT COUNT(*) FROM idempotent_record WHERE request_id = ?",
                requestId), "失败不占键");

        // 同一 requestId 以合法参数重试：正常受理
        InjectionReceiptResponse ok = planService.receipt(new InjectionReceiptRequest(requestId,
                planned.planId(), new BigDecimal("30"), TankFixtures.T.plusHours(1), 2));
        assertEquals("EXECUTED", ok.status());
    }

    @Test
    void duplicateReceiptDoesNotDoubleDeduct() {
        TankFixtures.registerStandard(tankService);
        InjectionPlanResponse planned = plan(new BigDecimal("30"), 1);

        InjectionReceiptRequest request = new InjectionReceiptRequest(
                TankFixtures.nextRequestId("RCPT"), planned.planId(),
                new BigDecimal("20"), TankFixtures.T.plusHours(1), 2);
        InjectionReceiptResponse first = planService.receipt(request);
        InjectionReceiptResponse replay = planService.receipt(request);

        assertTrue(replay.idempotentReplay());
        assertEquals(first.balanceKg(), replay.balanceKg());
        TankStatusResponse status = tankService.status(TankFixtures.STATION);
        assertEquals(0, status.balanceKg().compareTo(new BigDecimal("30.000")),
                "重复回执不重复扣量");
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'CONSUME'"),
                "扣量明细恰一条");
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'RELEASE'"),
                "释放明细恰一条");
    }

    @Test
    void receiptOnTerminalPlanIsRejected() {
        TankFixtures.registerStandard(tankService);
        InjectionPlanResponse planned = plan(new BigDecimal("30"), 1);
        planService.receipt(new InjectionReceiptRequest(TankFixtures.nextRequestId("RCPT"),
                planned.planId(), new BigDecimal("30"), TankFixtures.T.plusHours(1), 2));

        // 已执行计划再次回执（新 requestId）：业务拒绝
        assertThrows(BusinessRejectedException.class,
                () -> planService.receipt(new InjectionReceiptRequest(
                        TankFixtures.nextRequestId("RCPT"), planned.planId(),
                        new BigDecimal("1"), TankFixtures.T.plusHours(2), 3)));
    }

    @Test
    void cancelReleasesOnlyUnexecutedReservation() {
        TankFixtures.registerStandard(tankService);
        InjectionPlanResponse planned = plan(new BigDecimal("30"), 1);

        InjectionCancelResponse cancel = planService.cancel(new InjectionCancelRequest(
                TankFixtures.nextRequestId("CNCL"), planned.planId(),
                TankFixtures.T.plusHours(1), 2));

        assertEquals("CANCELLED", cancel.status());
        assertEquals(0, cancel.releasedKg().compareTo(new BigDecimal("30.000")));
        assertEquals(0, cancel.balanceKg().compareTo(new BigDecimal("50.000")), "取消不动余额");
        assertEquals(0, cancel.reservedKg().compareTo(BigDecimal.ZERO.setScale(3)));
        assertEquals(3, cancel.version());
    }

    @Test
    void cancelOnExecutedPlanIsRejected() {
        TankFixtures.registerStandard(tankService);
        InjectionPlanResponse planned = plan(new BigDecimal("30"), 1);
        planService.receipt(new InjectionReceiptRequest(TankFixtures.nextRequestId("RCPT"),
                planned.planId(), new BigDecimal("30"), TankFixtures.T.plusHours(1), 2));

        assertThrows(BusinessRejectedException.class,
                () -> planService.cancel(new InjectionCancelRequest(
                        TankFixtures.nextRequestId("CNCL"), planned.planId(),
                        TankFixtures.T.plusHours(2), 3)));
    }

    @Test
    void staleExpectedVersionIsConflict() {
        TankFixtures.registerStandard(tankService);
        assertThrows(VersionConflictException.class, () -> plan(new BigDecimal("10"), 5));
        assertEquals(0, count("SELECT COUNT(*) FROM injection_plan"), "版本冲突不落计划");
    }

    @Test
    void sameKeyDifferentVersionIsIdempotentConflict() {
        TankFixtures.registerStandard(tankService);
        String requestId = TankFixtures.nextRequestId("PLAN");
        planService.plan(new InjectionPlanRequest(TankFixtures.STATION, requestId,
                new BigDecimal("10"), TankFixtures.T, 1));
        // 同键异参（版本不同）：409
        assertThrows(IdempotentConflictException.class,
                () -> planService.plan(new InjectionPlanRequest(TankFixtures.STATION, requestId,
                        new BigDecimal("10"), TankFixtures.T, 2)));
    }

    @Test
    void unknownPlanIsNotFound() {
        TankFixtures.registerStandard(tankService);
        assertThrows(ResourceNotFoundException.class,
                () -> planService.receipt(new InjectionReceiptRequest(
                        TankFixtures.nextRequestId("RCPT"), 999999L,
                        new BigDecimal("1"), TankFixtures.T, 1)));
    }

    @Test
    void planOnUnboundStationIsNotFound() {
        assertThrows(ResourceNotFoundException.class,
                () -> planService.plan(new InjectionPlanRequest("ST02",
                        TankFixtures.nextRequestId("PLAN"), new BigDecimal("1"),
                        TankFixtures.T, 1)));
    }

    @Test
    void conservationHoldsAcrossPlanReceiptAndCancel() {
        TankRegisterResponse tank = TankFixtures.registerStandard(tankService);
        InjectionPlanResponse p1 = plan(new BigDecimal("30"), 1);
        InjectionPlanResponse p2 = plan(new BigDecimal("10"), 2);
        planService.receipt(new InjectionReceiptRequest(TankFixtures.nextRequestId("RCPT"),
                p1.planId(), new BigDecimal("25"), TankFixtures.T.plusHours(1), 3));
        planService.cancel(new InjectionCancelRequest(TankFixtures.nextRequestId("CNCL"),
                p2.planId(), TankFixtures.T.plusHours(2), 4));

        assertConservation(tank.tankId());
    }

    /**
     * 守恒勾稽：余额 = 登记+补液−消耗；预占 = 预占−释放−消耗；与储罐列一致。
     */
    void assertConservation(Long tankId) {
        BigDecimal balanceFromLedger = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE entry_type
                        WHEN 'REGISTER' THEN amount_kg
                        WHEN 'REFILL' THEN amount_kg
                        WHEN 'CONSUME' THEN -amount_kg ELSE 0 END), 0)
                FROM tank_ledger WHERE tank_id = ?
                """, BigDecimal.class, tankId);
        BigDecimal reservedFromLedger = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE entry_type
                        WHEN 'RESERVE' THEN amount_kg
                        WHEN 'RELEASE' THEN -amount_kg
                        WHEN 'CONSUME' THEN -amount_kg ELSE 0 END), 0)
                FROM tank_ledger WHERE tank_id = ?
                """, BigDecimal.class, tankId);
        TankStatusResponse status = tankService.status(TankFixtures.STATION);
        assertEquals(0, status.balanceKg().compareTo(balanceFromLedger),
                "余额须与台账守恒");
        assertEquals(0, status.reservedKg().compareTo(reservedFromLedger),
                "预占须与台账守恒");
    }
}
