package com.example.starter.tank;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.close.DailyCloseService;
import com.example.starter.web.BusinessRejectedException;
import com.example.starter.web.IdempotentConflictException;
import com.example.starter.web.ResourceNotFoundException;
import com.example.starter.web.VersionConflictException;
import com.example.starter.web.dto.DailyCloseRequest;
import com.example.starter.web.dto.DailyCloseResponse;
import com.example.starter.web.dto.TankPlanRequest;
import com.example.starter.web.dto.TankPlanResponse;
import com.example.starter.web.dto.TankRefillRequest;
import com.example.starter.web.dto.TankRegisterRequest;
import com.example.starter.web.dto.TankTransferApplyRequest;
import com.example.starter.web.dto.TankTransferApplyResponse;
import com.example.starter.web.dto.TankTransferCancelRequest;
import com.example.starter.web.dto.TankTransferCancelResponse;
import com.example.starter.web.dto.TankTransferConfirmRequest;
import com.example.starter.web.dto.TankTransferConfirmResponse;
import com.example.starter.web.dto.TankTransferRecomputeView;
import com.example.starter.web.dto.TankTransferView;
import com.example.starter.web.dto.TankView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨罐调拨主流程与失败分支：三位精度、同品种校验、双侧恰边界、原子预占、
 * 双人确认执行、撤销仅一次、版本乐观锁、幂等重放与异参冲突、守恒勾稽、
 * 日结展示转入转出且旧快照不改、从调拨单复算双侧预占与余额。
 */
class TankTransferServiceTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDate.of(2026, 9, 26).atTime(8, 0);
    private static final Instant APPLY_AT = Instant.parse("2026-09-26T01:00:00Z");
    private static final Instant CONFIRM_AT = Instant.parse("2026-09-26T02:00:00Z");
    private static final Instant CANCEL_AT = Instant.parse("2026-09-26T03:00:00Z");

    @Autowired
    private TankService tankService;
    @Autowired
    private TankTransferService transferService;
    @Autowired
    private DailyCloseService dailyCloseService;

    // ---------------------------------------------------------------- 辅助

    private long register(String station, String tankCode, String variety,
                          String capacity, String safety, String balance) {
        return tankService.register(new TankRegisterRequest(station, tankCode, "REG-" + tankCode,
                new BigDecimal(capacity), new BigDecimal(safety), new BigDecimal(balance),
                T0, variety)).tankId();
    }

    private TankTransferApplyResponse apply(String reqId, String source, String target,
                                            String variety, String qty, String batch) {
        return transferService.apply(new TankTransferApplyRequest(source, target, variety, reqId,
                new BigDecimal(qty), batch, List.of("OP-A", "OP-B"), APPLY_AT));
    }

    private TankTransferConfirmResponse confirm(long transferId, String reqId, long version,
                                                String actual, String credential) {
        return transferService.confirm(new TankTransferConfirmRequest(transferId, reqId, version,
                new BigDecimal(actual), credential, CONFIRM_AT));
    }

    private TankTransferCancelResponse cancel(long transferId, String reqId, long version) {
        return transferService.cancel(new TankTransferCancelRequest(transferId, reqId, version,
                CANCEL_AT));
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

    // ---------------------------------------------------------------- 申请

    @Test
    void applyReservesBothSidesAtomically() {
        long sourceId = register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        long targetId = register("ST02", "TK02", "VAR-TBT", "800", "50", "100");

        TankTransferApplyResponse resp = apply("TR-1", "TK01", "TK02", "VAR-TBT", "120", "TB-1");
        assertNotNull(resp.transferId());
        assertEquals("RESERVED", resp.status());
        assertEquals(1L, resp.transferVersion(), "调拨版本从1开始");
        assertBd("120", resp.sourceReservedKg());
        assertBd("120", resp.targetIncomingReservedKg());
        assertEquals(2L, resp.sourceVersion(), "源罐成功变更加1");
        assertEquals(2L, resp.targetVersion(), "目标罐成功变更加1");
        assertFalse(resp.idempotentReplay());

        TankView source = tankService.tankView("TK01");
        assertBd("500", source.balanceKg(), "申请只预占不动余额");
        assertBd("120", source.reservedKg());
        assertBd("380", source.availableKg());
        TankView target = tankService.tankView("TK02");
        assertBd("100", target.balanceKg());
        assertBd("120", target.incomingReservedKg());
        assertBd("100", target.availableKg(), "未入账接收预占不得计入可用量");

        // 双侧台账共用同一调拨标识
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE tank_id = ? "
                + "AND entry_type = 'TRANSFER_RESERVE' AND ref_type = 'TRANSFER' AND ref_id = ?",
                sourceId, resp.transferId()));
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE tank_id = ? "
                + "AND entry_type = 'TRANSFER_IN_RESERVE' AND ref_type = 'TRANSFER' AND ref_id = ?",
                targetId, resp.transferId()));
    }

    @Test
    void applyRejectsOverPrecisionInsteadOfRounding() {
        register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        register("ST02", "TK02", "VAR-TBT", "800", "50", "100");
        // 超过三位小数：拒绝而非舍入
        assertThrows(IllegalArgumentException.class,
                () -> apply("TR-1", "TK01", "TK02", "VAR-TBT", "10.1255", "TB-1"));
        assertThrows(IllegalArgumentException.class,
                () -> apply("TR-2", "TK01", "TK02", "VAR-TBT", "0.0001", "TB-2"));
        assertEquals(0, count("SELECT COUNT(*) FROM tank_transfer"), "超精度不得落调拨单");
        assertBd("0", tankService.tankView("TK01").reservedKg());
        assertBd("0", tankService.tankView("TK02").incomingReservedKg());
        // 三位小数恰允许
        TankTransferApplyResponse ok = apply("TR-3", "TK01", "TK02", "VAR-TBT", "10.125", "TB-3");
        assertBd("10.125", ok.quantityKg());
    }

    @Test
    void applyRejectsSameTankAndVarietyProblems() {
        register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        register("ST02", "TK02", "VAR-OTHER", "800", "50", "100");
        // 两罐须不同
        assertThrows(BusinessRejectedException.class,
                () -> apply("TR-1", "TK01", "TK01", "VAR-TBT", "10", "TB-1"));
        // 两罐品种不一致
        assertThrows(BusinessRejectedException.class,
                () -> apply("TR-2", "TK01", "TK02", "VAR-TBT", "10", "TB-2"));
        // 请求品种与罐登记品种不一致
        assertThrows(BusinessRejectedException.class,
                () -> apply("TR-3", "TK01", "TK02", "VAR-THIRD", "10", "TB-3"));
        // 罐不存在
        assertThrows(ResourceNotFoundException.class,
                () -> apply("TR-4", "TK01", "TK99", "VAR-TBT", "10", "TB-4"));
        assertEquals(0, count("SELECT COUNT(*) FROM tank_transfer"));
    }

    @Test
    void applyRejectsTanksWithoutVariety() {
        // 未登记品种号的罐未启用调拨
        register("ST01", "TK01", null, "1000", "100", "500");
        register("ST02", "TK02", "VAR-TBT", "800", "50", "100");
        assertThrows(BusinessRejectedException.class,
                () -> apply("TR-1", "TK01", "TK02", "VAR-TBT", "10", "TB-1"));
        assertThrows(BusinessRejectedException.class,
                () -> apply("TR-2", "TK02", "TK01", "VAR-TBT", "10", "TB-2"));
        assertEquals(0, count("SELECT COUNT(*) FROM tank_transfer"));
    }

    @Test
    void applyRejectsSameOperatorAndFailureFreesKey() {
        register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        register("ST02", "TK02", "VAR-TBT", "800", "50", "100");
        assertThrows(BusinessRejectedException.class, () -> transferService.apply(
                new TankTransferApplyRequest("TK01", "TK02", "VAR-TBT", "TR-1",
                        new BigDecimal("10"), "TB-1", List.of("OP-A", "OP-A"), APPLY_AT)));
        // 失败不占键：同键换成两名不同交接人后受理
        TankTransferApplyResponse ok = apply("TR-1", "TK01", "TK02", "VAR-TBT", "10", "TB-1");
        assertEquals("RESERVED", ok.status());
    }

    @Test
    void applySourceExactSafetyBoundaryAllowedAndBelowRejected() {
        register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        register("ST02", "TK02", "VAR-TBT", "1000", "50", "0");
        // 恰等安全储备允许：500 − 400 = 100 == 安全储备
        TankTransferApplyResponse ok = apply("TR-1", "TK01", "TK02", "VAR-TBT", "400", "TB-1");
        assertEquals("RESERVED", ok.status());
        assertBd("100", tankService.tankView("TK01").availableKg());
        // 再调 0.001：可用量 100 − 0.001 < 安全储备，拒绝且全单零预占
        assertThrows(BusinessRejectedException.class,
                () -> apply("TR-2", "TK01", "TK02", "VAR-TBT", "0.001", "TB-2"));
        assertBd("400", tankService.tankView("TK01").reservedKg());
        assertBd("400", tankService.tankView("TK02").incomingReservedKg(),
                "源侧拒绝时目标侧不得残留预占");
        assertEquals(1, count("SELECT COUNT(*) FROM tank_transfer"));
    }

    @Test
    void applyTargetExactCapacityAllowedAndOverflowRejected() {
        register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        register("ST02", "TK02", "VAR-TBT", "300", "50", "100");
        // 恰满允许：100 + 200 = 300 == 容量
        TankTransferApplyResponse ok = apply("TR-1", "TK01", "TK02", "VAR-TBT", "200", "TB-1");
        assertEquals("RESERVED", ok.status());
        // 再调 0.001：100 + 200 + 0.001 > 300，拒绝且双侧零新增预占
        assertThrows(BusinessRejectedException.class,
                () -> apply("TR-2", "TK01", "TK02", "VAR-TBT", "0.001", "TB-2"));
        assertBd("200", tankService.tankView("TK01").reservedKg(),
                "目标侧拒绝时源侧不得残留预占");
        assertBd("200", tankService.tankView("TK02").incomingReservedKg());
        assertEquals(1, count("SELECT COUNT(*) FROM tank_transfer"));
    }

    @Test
    void refillCannotUseTransferReservedTargetSpace() {
        register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        register("ST02", "TK02", "VAR-TBT", "300", "50", "100");
        apply("TR-1", "TK01", "TK02", "VAR-TBT", "150", "TB-1");
        // 目标罐：余额 100 + 接收预占 150 = 250，补液 60 → 310 > 300 拒绝
        assertThrows(BusinessRejectedException.class, () -> tankService.refill(
                new TankRefillRequest("TK02", "RF-1", 2L, "RB-1", new BigDecimal("60"),
                        new BigDecimal("20.000"), List.of("OP-A", "OP-B"), T0.plusHours(1))));
        // 补液 50 → 100 + 150 + 50 = 300 恰满允许
        tankService.refill(new TankRefillRequest("TK02", "RF-2", 2L, "RB-2",
                new BigDecimal("50"), new BigDecimal("20.000"),
                List.of("OP-A", "OP-B"), T0.plusHours(1)));
        assertBd("150", tankService.tankView("TK02").balanceKg());
    }

    @Test
    void incomingReservationNotCountedAsSourceAvailable() {
        // TK02 作为目标接收预占后，其作为源侧的可用量不变
        register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        register("ST02", "TK02", "VAR-TBT", "1000", "100", "200");
        apply("TR-1", "TK01", "TK02", "VAR-TBT", "100", "TB-1");
        TankView target = tankService.tankView("TK02");
        assertBd("100", target.incomingReservedKg());
        assertBd("200", target.availableKg(), "接收预占不得当作源可用量");
        // TK02 上原计划约束不变：可用量 200 − 101 < 安全储备 100 → 拦截
        TankPlanResponse blocked = tankService.createPlan(
                new TankPlanRequest("TK02", "PL-1", 2L, T0.plusHours(1), new BigDecimal("101")));
        assertEquals("BLOCKED", blocked.status());
        // 100 恰等安全储备边界允许（BLOCKED 计划亦变更版本，故期望版本为 3）
        TankPlanResponse ok = tankService.createPlan(
                new TankPlanRequest("TK02", "PL-2", 3L, T0.plusHours(1), new BigDecimal("100")));
        assertEquals("RESERVED", ok.status());
    }

    // ---------------------------------------------------------------- 执行确认

    @Test
    void confirmMovesBalanceAndReleasesBothSides() {
        long sourceId = register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        long targetId = register("ST02", "TK02", "VAR-TBT", "800", "50", "100");
        TankTransferApplyResponse applied = apply("TR-1", "TK01", "TK02", "VAR-TBT", "10.125", "TB-1");

        TankTransferConfirmResponse resp = confirm(applied.transferId(), "TC-1", 1,
                "10.125", "CRED-1");
        assertEquals("EXECUTED", resp.status());
        assertBd("10.125", resp.actualQtyKg());
        assertBd("489.875", resp.sourceBalanceKg());
        assertBd("110.125", resp.targetBalanceKg());
        assertEquals(2L, resp.transferVersion(), "成功加1");
        assertEquals(3L, resp.sourceVersion());
        assertEquals(3L, resp.targetVersion());

        TankView source = tankService.tankView("TK01");
        assertBd("0", source.reservedKg(), "执行后源预占释放");
        TankView target = tankService.tankView("TK02");
        assertBd("0", target.incomingReservedKg(), "执行后目标接收预占释放");

        // 守恒：10.125 公斤调拨后两罐总量不变，台账合计变化为 0
        BigDecimal total = source.balanceKg().add(target.balanceKg());
        assertBd("600", total, "调拨后两罐总量不变");
        BigDecimal net = jdbc.queryForObject(
                "SELECT COALESCE(SUM(balance_delta_kg),0) FROM tank_ledger "
                        + "WHERE ref_type = 'TRANSFER' AND ref_id = ?",
                BigDecimal.class, applied.transferId());
        assertBd("0", net, "双侧台账余额合计变化为0");
        assertBd("489.875", sumLedger("balance_delta_kg", sourceId));
        assertBd("110.125", sumLedger("balance_delta_kg", targetId));
        assertBd("0", sumLedger("reserved_delta_kg", sourceId));
        assertBd("0", sumLedger("incoming_reserved_delta_kg", targetId));

        TankTransferView view = transferService.transferView(applied.transferId());
        assertEquals("EXECUTED", view.status());
        assertEquals("CRED-1", view.credentialNo());
        assertEquals("OP-A", view.firstOperator());
        assertEquals("OP-B", view.secondOperator());
        assertEquals(2L, view.version());
    }

    @Test
    void confirmRejectsActualDifferentFromAppliedAndFailureFreesKey() {
        register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        register("ST02", "TK02", "VAR-TBT", "800", "50", "100");
        TankTransferApplyResponse applied = apply("TR-1", "TK01", "TK02", "VAR-TBT", "120", "TB-1");
        // 实际量不等于申请量：拒绝
        assertThrows(BusinessRejectedException.class,
                () -> confirm(applied.transferId(), "TC-1", 1, "119.999", "CRED-1"));
        assertEquals("RESERVED", transferService.transferView(applied.transferId()).status());
        assertBd("120", tankService.tankView("TK01").reservedKg(), "失败不得释放预占");
        // 失败不占键：同键修正为申请量后受理
        TankTransferConfirmResponse ok = confirm(applied.transferId(), "TC-1", 1, "120", "CRED-1");
        assertEquals("EXECUTED", ok.status());
    }

    @Test
    void confirmStaleVersionConflict409() {
        register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        register("ST02", "TK02", "VAR-TBT", "800", "50", "100");
        TankTransferApplyResponse applied = apply("TR-1", "TK01", "TK02", "VAR-TBT", "120", "TB-1");
        // 旧版 409
        assertThrows(VersionConflictException.class,
                () -> confirm(applied.transferId(), "TC-1", 2, "120", "CRED-1"));
        assertThrows(VersionConflictException.class,
                () -> cancel(applied.transferId(), "TX-1", 0));
        assertEquals("RESERVED", transferService.transferView(applied.transferId()).status());
        assertEquals(1L, transferService.transferView(applied.transferId()).version());
        // 正确版本受理
        confirm(applied.transferId(), "TC-2", 1, "120", "CRED-1");
        assertEquals(2L, transferService.transferView(applied.transferId()).version());
    }

    @Test
    void executedTransferCannotCancelOrReconfirmWithDifferentParams() {
        register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        register("ST02", "TK02", "VAR-TBT", "800", "50", "100");
        TankTransferApplyResponse applied = apply("TR-1", "TK01", "TK02", "VAR-TBT", "120", "TB-1");
        confirm(applied.transferId(), "TC-1", 1, "120", "CRED-1");
        // 已执行不得撤回
        assertThrows(BusinessRejectedException.class,
                () -> cancel(applied.transferId(), "TX-1", 2));
        // 已执行：不同量/不同凭据再次确认拒绝
        assertThrows(BusinessRejectedException.class,
                () -> confirm(applied.transferId(), "TC-2", 2, "100", "CRED-1"));
        assertThrows(BusinessRejectedException.class,
                () -> confirm(applied.transferId(), "TC-3", 2, "120", "CRED-OTHER"));
        // 同量同凭据重复确认（不同 requestId）：返回当前状态，不重复计量
        TankTransferConfirmResponse again = confirm(applied.transferId(), "TC-4", 2, "120", "CRED-1");
        assertEquals("EXECUTED", again.status());
        assertEquals(2L, again.transferVersion(), "重复确认不变更版本");
        assertBd("380", tankService.tankView("TK01").balanceKg());
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'TRANSFER_OUT'"),
                "重复确认不得重复扣量");
    }

    @Test
    void duplicateTransportBatchRejectedAcrossRequestIds() {
        register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        register("ST02", "TK02", "VAR-TBT", "800", "50", "100");
        apply("TR-1", "TK01", "TK02", "VAR-TBT", "10", "TB-1");
        // 运输批号全局唯一：不能以另一个 requestId 再次入账
        assertThrows(BusinessRejectedException.class,
                () -> apply("TR-2", "TK01", "TK02", "VAR-TBT", "10", "TB-1"));
        assertEquals(1, count("SELECT COUNT(*) FROM tank_transfer"));
        assertBd("10", tankService.tankView("TK01").reservedKg());
    }

    // ---------------------------------------------------------------- 撤销

    @Test
    void cancelReleasesBothSidesExactlyOnce() {
        long sourceId = register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        long targetId = register("ST02", "TK02", "VAR-TBT", "800", "50", "100");
        TankTransferApplyResponse applied = apply("TR-1", "TK01", "TK02", "VAR-TBT", "120", "TB-1");

        TankTransferCancelResponse resp = cancel(applied.transferId(), "TX-1", 1);
        assertEquals("CANCELLED", resp.status());
        assertBd("120", resp.releasedQtyKg());
        assertEquals(2L, resp.transferVersion());
        assertBd("0", tankService.tankView("TK01").reservedKg());
        assertBd("0", tankService.tankView("TK02").incomingReservedKg());
        assertBd("500", tankService.tankView("TK01").balanceKg(), "撤销不动余额");
        assertBd("100", tankService.tankView("TK02").balanceKg());

        // 重复撤销（不同 requestId）：不重复返还
        TankTransferCancelResponse again = cancel(applied.transferId(), "TX-2", 2);
        assertBd("0", again.releasedQtyKg());
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'TRANSFER_RELEASE'"),
                "撤销释放仅一次");
        assertBd("0", sumLedger("reserved_delta_kg", sourceId));
        assertBd("0", sumLedger("incoming_reserved_delta_kg", targetId));

        // 已撤销不得确认
        assertThrows(BusinessRejectedException.class,
                () -> confirm(applied.transferId(), "TC-1", 2, "120", "CRED-1"));
    }

    // ---------------------------------------------------------------- 幂等

    @Test
    void sameKeySameParamsReplaysFrozenResult() {
        register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        register("ST02", "TK02", "VAR-TBT", "800", "50", "100");
        TankTransferApplyResponse first = apply("TR-1", "TK01", "TK02", "VAR-TBT", "10", "TB-1");
        TankTransferApplyResponse replay = apply("TR-1", "TK01", "TK02", "VAR-TBT", "10", "TB-1");
        assertTrue(replay.idempotentReplay());
        assertEquals(first.transferId(), replay.transferId());
        assertEquals(1, count("SELECT COUNT(*) FROM tank_transfer"), "重放不得重复申请");
        assertBd("10", tankService.tankView("TK01").reservedKg());

        TankTransferConfirmResponse confirmFirst = confirm(first.transferId(), "TC-1", 1, "10", "CRED-1");
        TankTransferConfirmResponse confirmReplay = confirm(first.transferId(), "TC-1", 1, "10", "CRED-1");
        assertTrue(confirmReplay.idempotentReplay());
        assertEquals(confirmFirst.transferVersion(), confirmReplay.transferVersion());
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'TRANSFER_OUT'"),
                "重放不得重复扣量");
    }

    @Test
    void sameKeyDifferentParamsConflict409() {
        register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        register("ST02", "TK02", "VAR-TBT", "800", "50", "100");
        apply("TR-1", "TK01", "TK02", "VAR-TBT", "10", "TB-1");
        // 同键异参：409（数量、运输号、人员、时刻均属指纹）
        assertThrows(IdempotentConflictException.class,
                () -> apply("TR-1", "TK01", "TK02", "VAR-TBT", "20", "TB-1"));
        assertThrows(IdempotentConflictException.class,
                () -> apply("TR-1", "TK01", "TK02", "VAR-TBT", "10", "TB-2"));
        assertThrows(IdempotentConflictException.class, () -> transferService.apply(
                new TankTransferApplyRequest("TK01", "TK02", "VAR-TBT", "TR-1",
                        new BigDecimal("10"), "TB-1", List.of("OP-A", "OP-C"), APPLY_AT)));
        assertThrows(IdempotentConflictException.class, () -> transferService.apply(
                new TankTransferApplyRequest("TK01", "TK02", "VAR-TBT", "TR-1",
                        new BigDecimal("10"), "TB-1", List.of("OP-A", "OP-B"),
                        Instant.parse("2026-09-26T01:00:01Z"))));
        assertEquals(1, count("SELECT COUNT(*) FROM tank_transfer"));
        assertBd("10", tankService.tankView("TK01").reservedKg());
    }

    // ---------------------------------------------------------------- 日结与复算

    @Test
    void dailyCloseShowsTransferInAndOutAndOldSnapshotUnchanged() {
        register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        register("ST02", "TK02", "VAR-TBT", "800", "50", "100");
        // 先封存 2026-09-25（无检测无调拨）
        DailyCloseResponse sealed = dailyCloseService.close(
                new DailyCloseRequest("ST01", "DC-0", LocalDate.of(2026, 9, 25), BigDecimal.ZERO));
        assertEquals("SEALED", sealed.status());
        assertBd("0", sealed.transferOutKg());

        // 2026-09-26 执行 10.125 调拨（UTC 时刻落在当日）
        TankTransferApplyResponse applied = apply("TR-1", "TK01", "TK02", "VAR-TBT", "10.125", "TB-1");
        confirm(applied.transferId(), "TC-1", 1, "10.125", "CRED-1");

        // 后续日结展示转入和转出
        DailyCloseResponse sourceClose = dailyCloseService.close(
                new DailyCloseRequest("ST01", "DC-1", LocalDate.of(2026, 9, 26), BigDecimal.ZERO));
        assertBd("10.125", sourceClose.transferOutKg(), "源罐日结须展示转出");
        assertBd("0", sourceClose.transferInKg());
        DailyCloseResponse targetClose = dailyCloseService.close(
                new DailyCloseRequest("ST02", "DC-2", LocalDate.of(2026, 9, 26), BigDecimal.ZERO));
        assertBd("10.125", targetClose.transferInKg(), "目标罐日结须展示转入");
        assertBd("0", targetClose.transferOutKg());

        // 旧日结快照不改：封存行保持原值
        assertEquals("SEALED", jdbc.queryForObject(
                "SELECT status FROM daily_close WHERE station_id = 1 AND biz_date = '2026-09-25'",
                String.class));
        assertBd("0.0000", jdbc.queryForObject(
                "SELECT deviation_pct FROM daily_close WHERE station_id = 1 AND biz_date = '2026-09-25'",
                BigDecimal.class));
        assertEquals(0, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'TRANSFER_OUT' "
                + "AND biz_at >= '2026-09-25' AND biz_at < '2026-09-26'"),
                "调拨不得改写旧日台账");
    }

    @Test
    void recomputeFromTransferMatchesBothSides() {
        long sourceId = register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        long targetId = register("ST02", "TK02", "VAR-TBT", "800", "50", "100");
        TankTransferApplyResponse applied = apply("TR-1", "TK01", "TK02", "VAR-TBT", "10.125", "TB-1");

        // 执行前复算：双侧预占在账
        TankTransferRecomputeView before = transferService.recompute(applied.transferId());
        assertEquals("RESERVED", before.status());
        assertBd("0", before.transferredKg());
        assertBd("10.125", before.sourceReservedDeltaKg());
        assertBd("10.125", before.targetIncomingReservedDeltaKg());
        assertBd("0", before.netBalanceDeltaKg());
        assertBd("500", before.sourceBalanceKg());
        assertBd("100", before.targetBalanceKg());
        assertBd("10.125", before.sourceReservedKg());
        assertBd("10.125", before.targetIncomingReservedKg());

        confirm(applied.transferId(), "TC-1", 1, "10.125", "CRED-1");
        TankTransferRecomputeView after = transferService.recompute(applied.transferId());
        assertEquals("EXECUTED", after.status());
        assertBd("10.125", after.transferredKg(), "调拨流量=实际执行量");
        assertBd("0", after.sourceReservedDeltaKg(), "执行后本单源预占净变动为0");
        assertBd("0", after.targetIncomingReservedDeltaKg());
        assertBd("0", after.netBalanceDeltaKg(), "双侧余额合计变化为0");
        assertBd("489.875", after.sourceBalanceKg());
        assertBd("110.125", after.targetBalanceKg());
        assertBd("0", after.sourceReservedKg());
        assertBd("0", after.targetIncomingReservedKg());
        // 与罐当前状态一致
        assertEquals(0, after.sourceBalanceKg().compareTo(
                tankService.tankView("TK01").balanceKg()));
        assertEquals(0, after.targetBalanceKg().compareTo(
                tankService.tankView("TK02").balanceKg()));
        assertEquals(sourceId, after.sourceTankId());
        assertEquals(targetId, after.targetTankId());
    }

    @Test
    void transferQueriesListByTank() {
        register("ST01", "TK01", "VAR-TBT", "1000", "100", "500");
        register("ST02", "TK02", "VAR-TBT", "800", "50", "100");
        TankTransferApplyResponse applied = apply("TR-1", "TK01", "TK02", "VAR-TBT", "10", "TB-1");
        assertEquals(1, transferService.transfers("TK01").size());
        assertEquals(1, transferService.transfers("TK02").size());
        assertEquals(applied.transferId(), transferService.transfers("TK01").get(0).transferId());
        assertThrows(ResourceNotFoundException.class, () -> transferService.transfers("TK99"));
        assertThrows(ResourceNotFoundException.class, () -> transferService.transferView(999L));
        assertThrows(ResourceNotFoundException.class, () -> transferService.recompute(999L));
        TankTransferView view = transferService.transferView(applied.transferId());
        assertNull(view.executedAt());
        assertNull(view.cancelledAt());
        assertEquals(Instant.parse("2026-09-26T01:00:00Z"), view.appliedAt(), "申请时刻按UTC回放");
    }
}
