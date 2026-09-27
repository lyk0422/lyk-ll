package com.example.starter.tank;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.web.BusinessRejectException;
import com.example.starter.web.IdempotentConflictException;
import com.example.starter.web.ResourceNotFoundException;
import com.example.starter.web.VersionConflictException;
import com.example.starter.web.dto.InjectionCancelRequest;
import com.example.starter.web.dto.InjectionCancelResponse;
import com.example.starter.web.dto.InjectionIssueRequest;
import com.example.starter.web.dto.InjectionIssueResponse;
import com.example.starter.web.dto.InjectionReceiptRequest;
import com.example.starter.web.dto.InjectionReceiptResponse;
import com.example.starter.web.dto.ReplenishRequest;
import com.example.starter.web.dto.ReplenishResponse;
import com.example.starter.web.dto.TankRegisterRequest;
import com.example.starter.web.dto.TankRegisterResponse;
import com.example.starter.web.dto.TankStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 储罐交接台账 H2 事务测试：登记校验、预占/安全储备闭区间、回执消耗与释放、
 * 取消、补液双人与浓度闭区间/容量、版本号与幂等、台账逐笔守恒。
 */
class TankServiceTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 9, 0);

    @Autowired
    private TankService tankService;

    private TankRegisterRequest registerReq(String requestId, String station, String capacity,
                                            String safety, String initial) {
        return new TankRegisterRequest(station, requestId, "TK-" + requestId,
                new BigDecimal(capacity), new BigDecimal(safety), new BigDecimal(initial), T0);
    }

    private void registerFullTank() {
        tankService.register(registerReq("REG-1", "ST01", "100.000", "20.000", "50.000"));
    }

    private InjectionIssueRequest issueReq(String requestId, String required, int version,
                                           LocalDateTime time) {
        return new InjectionIssueRequest("ST01", requestId, new BigDecimal(required), version, time);
    }

    private InjectionReceiptRequest receiptReq(String requestId, String planRequestId,
                                               String actual, int version, LocalDateTime time) {
        return new InjectionReceiptRequest("ST01", requestId, planRequestId,
                new BigDecimal(actual), version, time);
    }

    private InjectionCancelRequest cancelReq(String requestId, String planRequestId,
                                             int version, LocalDateTime time) {
        return new InjectionCancelRequest("ST01", requestId, planRequestId, version, time);
    }

    // ===== 登记 =====

    @Test
    void registerCreatesTankVersionOneAndOpeningLedger() {
        TankRegisterResponse resp = tankService.register(
                registerReq("REG-A", "ST01", "100.000", "20.000", "50.000"));
        assertEquals(1, resp.version());
        assertEquals(0, new BigDecimal("50.000").compareTo(resp.balanceKg()));
        assertEquals(0, new BigDecimal("0.000").compareTo(resp.reservedKg()));
        assertEquals(0, new BigDecimal("50.000").compareTo(resp.availableKg()));
        assertFalse(resp.idempotentReplay());

        TankStatusResponse status = tankService.status("ST01");
        assertEquals(1, status.version());
        assertEquals(1, status.ledger().size(), "期初余额须登记一条 REGISTER 流水");
        assertEquals("REGISTER", status.ledger().get(0).entryType());
    }

    @Test
    void registerWithZeroInitialBalanceCreatesNoAmountLedger() {
        tankService.register(registerReq("REG-Z", "ST01", "100.000", "0.000", "0.000"));
        TankStatusResponse status = tankService.status("ST01");
        assertEquals(0, new BigDecimal("0.000").compareTo(status.balanceKg()));
        assertTrue(status.ledger().isEmpty());
    }

    @Test
    void registerRejectsSafetyReserveNotBelowCapacity() {
        // 恰等容量非法：必须 0 ≤ 安全储备 < 容量
        assertThrows(IllegalArgumentException.class, () -> tankService.register(
                registerReq("REG-BAD1", "ST01", "20.000", "20.000", "0.000")));
        assertThrows(IllegalArgumentException.class, () -> tankService.register(
                registerReq("REG-BAD2", "ST01", "20.000", "21.000", "0.000")));
    }

    @Test
    void registerRejectsInitialBalanceOverCapacity() {
        assertThrows(IllegalArgumentException.class, () -> tankService.register(
                registerReq("REG-BAD3", "ST01", "100.000", "10.000", "100.001")));
    }

    @Test
    void registerTwiceForSameStationIsRejected422() {
        tankService.register(registerReq("REG-D1", "ST01", "100.000", "20.000", "50.000"));
        assertThrows(BusinessRejectException.class, () -> tankService.register(
                registerReq("REG-D2", "ST01", "100.000", "20.000", "50.000")));
    }

    @Test
    void registerUnknownStationReturns404AndTankOperationsOnUnboundReturn404() {
        assertThrows(ResourceNotFoundException.class, () -> tankService.register(
                registerReq("REG-X", "ST99", "100.000", "20.000", "50.000")));
        assertThrows(ResourceNotFoundException.class, () -> tankService.status("ST99"));
        // 已存在但未绑定储罐的门站
        assertThrows(ResourceNotFoundException.class, () -> tankService.status("ST02"));
        assertThrows(ResourceNotFoundException.class, () -> tankService.issue(
                issueReq("I-X", "10.000", 1, T0)));
    }

    // ===== 预占与安全储备 =====

    @Test
    void issueReservesAndBumpsVersionWhenAvailableStaysAtOrAboveSafety() {
        registerFullTank(); // 余额50 安全20 可用50
        // 预占30：预占后可用=20，恰等安全储备，允许
        InjectionIssueResponse resp = tankService.issue(issueReq("IS-1", "30.000", 1, T0));
        assertTrue(resp.issued());
        assertEquals(0, new BigDecimal("30.000").compareTo(resp.reservedKg()));
        assertEquals(0, new BigDecimal("20.000").compareTo(resp.availableKg()));
        assertEquals(2, resp.version(), "成功写操作版本加1");
        assertFalse(resp.pendingEventCreated());
    }

    @Test
    void issueBelowSafetyCreatesPendingEventAndDoesNotReserveOrIssue() {
        registerFullTank(); // 可用50 安全20
        InjectionIssueResponse resp = tankService.issue(issueReq("IS-2", "30.001", 1, T0));
        assertFalse(resp.issued(), "预占后可用19.999<20，加注不得下发");
        assertNull(resp.planId());
        assertTrue(resp.pendingEventCreated());
        assertEquals(0, new BigDecimal("0.000").compareTo(resp.reservedKg()), "预占不得增加");
        assertEquals(0, new BigDecimal("50.000").compareTo(resp.balanceKg()));
        assertEquals(1, resp.version(), "被拦截不变更版本");

        TankStatusResponse status = tankService.status("ST01");
        assertEquals(0, new BigDecimal("0.000").compareTo(status.reservedKg()));
        assertEquals(1, count("SELECT COUNT(*) FROM pending_replenish_event WHERE source_request_id = 'IS-2' "
                + "AND status = 'OPEN'"));
        assertEquals(0, count("SELECT COUNT(*) FROM injection_plan WHERE request_id = 'IS-2'"),
                "被拦截不得生成加注计划");
        assertEquals(1, status.ledger().size(), "被拦截不得写台账计量流水");
    }

    @Test
    void issueWithStaleExpectedVersionReturns409() {
        registerFullTank();
        assertThrows(VersionConflictException.class,
                () -> tankService.issue(issueReq("IS-V", "5.000", 2, T0)));
    }

    // ===== 回执 =====

    @Test
    void receiptConvertsReserveToConsumptionAndReleasesUnused() {
        registerFullTank(); // 余额50 安全20
        tankService.issue(issueReq("IS-R", "30.000", 1, T0)); // 预占30，版本2
        // 实际消耗 25，释放 5
        InjectionReceiptResponse resp = tankService.receipt(
                receiptReq("RC-1", "IS-R", "25.000", 2, T0.plusMinutes(1)));
        assertEquals(0, new BigDecimal("25.000").compareTo(resp.consumedKg()));
        assertEquals(0, new BigDecimal("5.000").compareTo(resp.releasedKg()));
        assertEquals(0, new BigDecimal("25.000").compareTo(resp.balanceKg()), "余额50-25=25");
        assertEquals(0, new BigDecimal("0.000").compareTo(resp.reservedKg()), "预占全部结清");
        assertEquals(3, resp.version());
        assertLedgerConservation();
    }

    @Test
    void receiptActualGreaterThanReservedIsRejected422() {
        registerFullTank();
        tankService.issue(issueReq("IS-G", "10.000", 1, T0));
        assertThrows(BusinessRejectException.class, () -> tankService.receipt(
                receiptReq("RC-G", "IS-G", "10.001", 2, T0.plusMinutes(1))));
        // 拒绝不扣量、不释放
        TankStatusResponse status = tankService.status("ST01");
        assertEquals(0, new BigDecimal("50.000").compareTo(status.balanceKg()));
        assertEquals(0, new BigDecimal("10.000").compareTo(status.reservedKg()));
    }

    @Test
    void duplicateReceiptSameRequestReplaysAndDoesNotDeductTwice() {
        registerFullTank();
        tankService.issue(issueReq("IS-D", "30.000", 1, T0));
        InjectionReceiptRequest req = receiptReq("RC-D", "IS-D", "30.000", 2, T0.plusMinutes(1));
        InjectionReceiptResponse first = tankService.receipt(req);
        assertFalse(first.idempotentReplay());
        InjectionReceiptResponse replay = tankService.receipt(req);
        assertTrue(replay.idempotentReplay());
        assertEquals(0, new BigDecimal("20.000").compareTo(replay.balanceKg()), "重复回执不重复扣量");
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'CONSUME'"));
    }

    @Test
    void secondDistinctReceiptOnExecutedPlanIsRejected422() {
        registerFullTank();
        tankService.issue(issueReq("IS-E", "10.000", 1, T0));
        tankService.receipt(receiptReq("RC-E1", "IS-E", "8.000", 2, T0.plusMinutes(1)));
        assertThrows(BusinessRejectException.class, () -> tankService.receipt(
                receiptReq("RC-E2", "IS-E", "1.000", 3, T0.plusMinutes(2))));
    }

    @Test
    void receiptOfUnknownPlanReturns404() {
        registerFullTank();
        assertThrows(ResourceNotFoundException.class, () -> tankService.receipt(
                receiptReq("RC-X", "NO-SUCH", "1.000", 1, T0)));
    }

    // ===== 取消 =====

    @Test
    void cancelReleasesOnlyUnexecutedReservation() {
        registerFullTank();
        tankService.issue(issueReq("IS-C", "30.000", 1, T0));
        InjectionCancelResponse resp = tankService.cancel(
                cancelReq("CC-1", "IS-C", 2, T0.plusMinutes(1)));
        assertEquals(0, new BigDecimal("30.000").compareTo(resp.releasedKg()));
        assertEquals(0, new BigDecimal("50.000").compareTo(resp.balanceKg()), "取消不动余额");
        assertEquals(0, new BigDecimal("0.000").compareTo(resp.reservedKg()));
        assertEquals(3, resp.version());
        assertLedgerConservation();

        // 取消后不能回执
        assertThrows(BusinessRejectException.class, () -> tankService.receipt(
                receiptReq("RC-AFTER", "IS-C", "1.000", 3, T0.plusMinutes(2))));
        // 重复取消 422
        assertThrows(BusinessRejectException.class, () -> tankService.cancel(
                cancelReq("CC-2", "IS-C", 3, T0.plusMinutes(3))));
    }

    @Test
    void cancelAfterReceiptIsRejected422() {
        registerFullTank();
        tankService.issue(issueReq("IS-CR", "10.000", 1, T0));
        tankService.receipt(receiptReq("RC-CR", "IS-CR", "10.000", 2, T0.plusMinutes(1)));
        assertThrows(BusinessRejectException.class, () -> tankService.cancel(
                cancelReq("CC-CR", "IS-CR", 3, T0.plusMinutes(2))));
    }

    // ===== 补液 =====

    private ReplenishRequest replenishReq(String requestId, String batch, String quantity,
                                          String concentration, String h1, String h2,
                                          int version, LocalDateTime time) {
        return new ReplenishRequest("ST01", requestId, batch, new BigDecimal(quantity),
                new BigDecimal(concentration), h1, h2, version, time);
    }

    @Test
    void replenishAtomicallyAddsBalanceAndClosesSatisfiablePendingEvents() {
        registerFullTank(); // 余额50 安全20
        // 预占 30.001 被拦截 → OPEN 事件
        InjectionIssueResponse blocked = tankService.issue(issueReq("IS-P", "30.001", 1, T0));
        assertFalse(blocked.issued());

        // 补液 10kg（验收浓度恰下限19.000 允许），余额 60，可用 60，可满足该事件（需要30.001）
        ReplenishResponse resp = tankService.replenish(
                replenishReq("RP-1", "BATCH-1", "10.000", "19.000", "H01", "H02", 1, T0.plusMinutes(2)));
        assertEquals(0, new BigDecimal("60.000").compareTo(resp.balanceAfterKg()));
        assertEquals(List.of("IS-P"), resp.closedPendingRequests(),
                "补液原子关闭可满足事件，但不得重发旧加注命令");
        assertEquals(2, resp.version());
        assertEquals(1, count("SELECT COUNT(*) FROM pending_replenish_event WHERE source_request_id = 'IS-P' "
                + "AND status = 'CLOSED'"));
        assertEquals(0, count("SELECT COUNT(*) FROM injection_plan WHERE request_id = 'IS-P'"),
                "关闭事件不得自动重发/补建旧加注计划");
        assertLedgerConservation();
    }

    @Test
    void replenishConcentrationUpperBoundInclusiveAllowed() {
        registerFullTank();
        ReplenishResponse resp = tankService.replenish(
                replenishReq("RP-U", "BATCH-U", "1.000", "21.000", "H01", "H02", 1, T0));
        assertEquals(0, new BigDecimal("51.000").compareTo(resp.balanceAfterKg()));
    }

    @Test
    void replenishRejectsSameHandler() {
        registerFullTank();
        assertThrows(BusinessRejectException.class, () -> tankService.replenish(
                replenishReq("RP-S", "BATCH-S", "1.000", "20.000", "H01", "H01", 1, T0)));
    }

    @Test
    void replenishRejectsConcentrationOutOfClosedRange() {
        registerFullTank();
        assertThrows(BusinessRejectException.class, () -> tankService.replenish(
                replenishReq("RP-C1", "BATCH-C1", "1.000", "18.999", "H01", "H02", 1, T0)));
        assertThrows(BusinessRejectException.class, () -> tankService.replenish(
                replenishReq("RP-C2", "BATCH-C2", "1.000", "21.001", "H01", "H02", 1, T0)));
    }

    @Test
    void replenishRejectsOverCapacityExactlyAtCapacityAllowed() {
        tankService.register(registerReq("REG-CAP", "ST01", "100.000", "0.000", "99.500"));
        // 恰补到容量 100 允许
        ReplenishResponse ok = tankService.replenish(
                replenishReq("RP-OK", "BATCH-OK", "0.500", "20.000", "H01", "H02", 1, T0));
        assertEquals(0, new BigDecimal("100.000").compareTo(ok.balanceAfterKg()));
        // 再补 0.001 超容量
        assertThrows(BusinessRejectException.class, () -> tankService.replenish(
                replenishReq("RP-OVER", "BATCH-OVER", "0.001", "20.000", "H01", "H02", 2, T0.plusMinutes(1))));
    }

    @Test
    void replenishRejectsDuplicateTransportBatch() {
        registerFullTank();
        tankService.replenish(replenishReq("RP-D1", "DUP-BATCH", "1.000", "20.000", "H01", "H02", 1, T0));
        // 不同 requestId 但同批号 → 422
        assertThrows(BusinessRejectException.class, () -> tankService.replenish(
                replenishReq("RP-D2", "DUP-BATCH", "2.000", "20.000", "H03", "H04", 2, T0.plusMinutes(1))));
    }

    @Test
    void replenishDoesNotCloseEventsItCannotYetSatisfy() {
        // 容量100 安全0 余额0；补液5不足以满足所需30.001的事件，事件保持 OPEN
        tankService.register(registerReq("REG-LOW", "ST01", "100.000", "0.000", "0.000"));
        tankService.issue(issueReq("IS-BIG", "30.001", 1, T0));
        ReplenishResponse resp = tankService.replenish(
                replenishReq("RP-SMALL", "BATCH-SMALL", "5.000", "20.000", "H01", "H02", 1, T0.plusMinutes(1)));
        assertTrue(resp.closedPendingRequests().isEmpty());
        assertEquals(1L, countOpen());
    }

    @Test
    void sameKeyDifferentParamsReturns409AndFailureDoesNotOccupyKey() {
        registerFullTank();
        tankService.issue(issueReq("IS-IDEM", "10.000", 1, T0));
        // 同键异参 409
        assertThrows(IdempotentConflictException.class, () -> tankService.issue(
                issueReq("IS-IDEM", "11.000", 1, T0)));
        // 失败不占键：另一个操作的同键合法语义不被占用（这里验证异参未落任何业务数据）
        assertEquals(0, count("SELECT COUNT(*) FROM injection_plan WHERE request_id = 'IS-IDEM' "
                + "AND required_kg = 11.000"));
    }

    @Test
    void decimalTextEquivalenceReplaysAsSameParams() {
        registerFullTank();
        // 30.000 与 30.0 文本等价：同键重放，不得新建计划
        InjectionIssueResponse first = tankService.issue(
                new InjectionIssueRequest("ST01", "IS-EQ", new BigDecimal("30.000"), 1, T0));
        InjectionIssueResponse again = tankService.issue(
                new InjectionIssueRequest("ST01", "IS-EQ", new BigDecimal("30.0"), 1, T0));
        assertTrue(again.idempotentReplay());
        assertEquals(first.planId(), again.planId());
        assertEquals(1, count("SELECT COUNT(*) FROM injection_plan WHERE request_id = 'IS-EQ'"));
    }

    @Test
    void failedBusinessAttemptDoesNotOccupyIdempotencyKey() {
        registerFullTank(); // 版本1
        // 首次以错误版本尝试预占 → 409 失败回滚，不占幂等键
        assertThrows(VersionConflictException.class, () -> tankService.issue(
                issueReq("IS-RETRY", "10.000", 9, T0)));
        // 同一 requestId 以正确版本重试 → 正常首次受理（非重放）
        InjectionIssueResponse retried = tankService.issue(issueReq("IS-RETRY", "10.000", 1, T0));
        assertTrue(retried.issued());
        assertFalse(retried.idempotentReplay());
        assertEquals(1, count("SELECT COUNT(*) FROM injection_plan WHERE request_id = 'IS-RETRY'"));
    }

    /**
     * 台账守恒：当前余额 = 期初 + 补液 − 消耗；当前预占 = 预占 − 消耗 − 释放。
     */
    private void assertLedgerConservation() {
        TankStatusResponse status = tankService.status("ST01");
        BigDecimal opening = ledgerSum("REGISTER");
        BigDecimal replenish = ledgerSum("REPLENISH");
        BigDecimal consume = ledgerSum("CONSUME");
        BigDecimal reserve = ledgerSum("RESERVE");
        BigDecimal release = ledgerSum("RELEASE");
        BigDecimal expectBalance = opening.add(replenish).subtract(consume);
        BigDecimal expectReserved = reserve.subtract(consume).subtract(release);
        assertEquals(0, expectBalance.compareTo(status.balanceKg()),
                "余额守恒：期初+补液-消耗=" + expectBalance + " 实际=" + status.balanceKg());
        assertEquals(0, expectReserved.compareTo(status.reservedKg()),
                "预占守恒：预占-消耗-释放=" + expectReserved + " 实际=" + status.reservedKg());
    }

    private BigDecimal ledgerSum(String entryType) {
        BigDecimal value = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_kg),0) FROM tank_ledger WHERE entry_type = ?",
                BigDecimal.class, entryType);
        return value == null ? BigDecimal.ZERO : value;
    }

    private long countOpen() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM pending_replenish_event WHERE status = 'OPEN'", Long.class);
    }
}
