package com.example.starter.tank;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.close.DailyCloseService;
import com.example.starter.web.BusinessRejectedException;
import com.example.starter.web.IdempotentConflictException;
import com.example.starter.web.ResourceNotFoundException;
import com.example.starter.web.VersionConflictException;
import com.example.starter.web.dto.DailyCloseRequest;
import com.example.starter.web.dto.DailyCloseResponse;
import com.example.starter.web.dto.TankCancelRequest;
import com.example.starter.web.dto.TankCancelResponse;
import com.example.starter.web.dto.TankPlanRequest;
import com.example.starter.web.dto.TankPlanResponse;
import com.example.starter.web.dto.TankReceiptRequest;
import com.example.starter.web.dto.TankReceiptResponse;
import com.example.starter.web.dto.TankRefillRequest;
import com.example.starter.web.dto.TankRefillResponse;
import com.example.starter.web.dto.TankRegisterRequest;
import com.example.starter.web.dto.TankRegisterResponse;
import com.example.starter.web.dto.TankView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 储罐交接台账主流程与失败分支：登记校验、预占/回执/取消、双人补液入账、
 * 版本乐观锁、幂等重放与异参冲突、守恒勾稽、日结冻结不受后续补液影响。
 */
class TankServiceTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDate.of(2026, 9, 26).atTime(8, 0);

    @Autowired
    private TankService tankService;
    @Autowired
    private DailyCloseService dailyCloseService;

    private TankRegisterResponse register(String tankCode, String capacity, String safety, String balance) {
        return tankService.register(new TankRegisterRequest("ST01", tankCode, "REG-" + tankCode,
                new BigDecimal(capacity), new BigDecimal(safety), new BigDecimal(balance), T0));
    }

    private TankPlanResponse plan(String tankCode, String reqId, long version, String qty) {
        return tankService.createPlan(new TankPlanRequest(tankCode, reqId, version,
                T0.plusHours(1), new BigDecimal(qty)));
    }

    private TankReceiptResponse receipt(long planId, String reqId, long version, String qty) {
        return tankService.receipt(new TankReceiptRequest(planId, reqId, version,
                new BigDecimal(qty), T0.plusHours(2)));
    }

    private TankCancelResponse cancel(long planId, String reqId, long version) {
        return tankService.cancel(new TankCancelRequest(planId, reqId, version, T0.plusHours(3)));
    }

    private TankRefillResponse refill(String tankCode, String reqId, long version,
                                      String batch, String qty, String concentration) {
        return tankService.refill(new TankRefillRequest(tankCode, reqId, version, batch,
                new BigDecimal(qty), new BigDecimal(concentration),
                List.of("OP-A", "OP-B"), T0.plusHours(4)));
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

    // ---------------------------------------------------------------- 登记

    @Test
    void registerCreatesTankWithVersionOne() {
        TankRegisterResponse resp = register("TK01", "1000.000", "100.000", "500.000");
        assertNotNull(resp.tankId());
        assertEquals(1L, resp.version(), "版本从1开始");
        assertBd("500.000", resp.balanceKg());
        assertBd("0", resp.reservedKg());
        assertFalse(resp.idempotentReplay());

        TankView view = tankService.tankView("TK01");
        assertBd("1000.000", view.capacityKg());
        assertBd("100.000", view.safetyReserveKg());
        assertBd("500.000", view.availableKg());
        assertEquals(1L, view.version());
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE tank_id = ? AND entry_type = 'REGISTER'",
                resp.tankId()), "登记须落 REGISTER 台账分录");
    }

    @Test
    void registerRejectsInvalidParams() {
        // 安全储备恰等容量：违反 0≤安全储备<容量
        assertThrows(IllegalArgumentException.class, () -> register("TK02", "1000", "1000", "500"));
        // 安全储备超容量
        assertThrows(IllegalArgumentException.class, () -> register("TK02", "1000", "1000.001", "500"));
        // 期初余额超容量
        assertThrows(IllegalArgumentException.class, () -> register("TK02", "1000", "100", "1000.001"));
        // 超过三位小数
        assertThrows(IllegalArgumentException.class, () -> register("TK02", "1000", "100", "500.0001"));
        // 门站不存在
        assertThrows(ResourceNotFoundException.class, () -> tankService.register(
                new TankRegisterRequest("ST99", "TK02", "REG-TK02", new BigDecimal("1000"),
                        new BigDecimal("100"), new BigDecimal("500"), T0)));
        assertEquals(0, count("SELECT COUNT(*) FROM tank"), "非法参数不得落罐");
    }

    @Test
    void registerDuplicateRejectedButSameKeyReplays() {
        TankRegisterResponse first = register("TK01", "1000", "100", "500");
        // 同键同参：重放首次结果
        TankRegisterResponse replay = register("TK01", "1000", "100", "500");
        assertTrue(replay.idempotentReplay());
        assertEquals(first.tankId(), replay.tankId());
        assertEquals(1, count("SELECT COUNT(*) FROM tank"), "重放不得重复登记");

        // 不同 requestId 重复罐编码：业务拒绝
        assertThrows(BusinessRejectedException.class, () -> tankService.register(
                new TankRegisterRequest("ST01", "TK01", "REG-OTHER", new BigDecimal("1000"),
                        new BigDecimal("100"), new BigDecimal("500"), T0)));
        // 不同罐编码但门站已绑定：业务拒绝
        assertThrows(BusinessRejectedException.class, () -> register("TK02", "1000", "100", "500"));
        assertEquals(1, count("SELECT COUNT(*) FROM tank"));
    }

    // ---------------------------------------------------------------- 加注计划

    @Test
    void planReservesAndExactSafetyBoundaryAllowed() {
        register("TK01", "1000", "100", "500");
        TankPlanResponse p1 = plan("TK01", "PL-1", 1, "300");
        assertEquals("RESERVED", p1.status());
        assertTrue(p1.dispatched());
        assertBd("300", p1.reservedKg());
        assertBd("200", p1.availableKg());
        assertEquals(2L, p1.version(), "成功变更加1");

        // 恰等安全储备允许下发：可用量 200 − 100 = 100 == 安全储备
        TankPlanResponse p2 = plan("TK01", "PL-2", 2, "100");
        assertEquals("RESERVED", p2.status(), "预占后可用量恰等安全储备须允许");
        assertBd("100", p2.availableKg());
        assertEquals(2, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'RESERVE'"));
    }

    @Test
    void planBlockedBelowSafetyCreatesRefillEventAndNotDispatched() {
        register("TK01", "1000", "100", "500");
        // 预占后可用量 500−401=99 < 安全储备 100：不下发
        TankPlanResponse blocked = plan("TK01", "PL-1", 1, "401");
        assertEquals("BLOCKED", blocked.status());
        assertFalse(blocked.dispatched(), "低于安全储备不得下发加注");
        assertNotNull(blocked.refillEventId(), "须生成待补液事件");
        assertBd("0", blocked.reservedKg());
        assertBd("500", blocked.availableKg());

        TankRefillEvent event = tankService.refillEvents("TK01").get(0);
        assertEquals("OPEN", event.status());
        assertEquals("PLAN", event.source());
        assertEquals(blocked.planId(), event.planId());
        assertBd("401", event.requiredQtyKg());
        // 缺口 = 401 − (500 − 100) = 1
        assertBd("1.000", event.deficitQtyKg());
        assertEquals(0, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'RESERVE'"),
                "未下发不得产生预占分录");
    }

    @Test
    void planVersionMismatchConflict() {
        register("TK01", "1000", "100", "500");
        assertThrows(VersionConflictException.class, () -> plan("TK01", "PL-V", 2, "100"));
        assertThrows(ResourceNotFoundException.class, () -> plan("TK99", "PL-V2", 1, "100"));
        assertEquals(0, count("SELECT COUNT(*) FROM tank_plan"), "版本冲突不得落计划");
    }

    // ---------------------------------------------------------------- 回执

    @Test
    void receiptConvertsReservationAndReleasesUnused() {
        register("TK01", "1000", "100", "500");
        TankPlanResponse p = plan("TK01", "PL-1", 1, "300");

        TankReceiptResponse r = receipt(p.planId(), "RC-1", 2, "250");
        assertEquals("EXECUTED", r.status());
        assertBd("250", r.actualQtyKg());
        assertBd("50", r.releasedQtyKg(), "未用部分须释放");
        assertBd("250", r.balanceKg());
        assertBd("0", r.reservedKg());
        assertEquals(3L, r.version());

        // 守恒：余额=期初-消耗，预占=预占合计-消耗-释放
        assertBd("250", sumLedger("balance_delta_kg", p.tankId()));
        assertBd("0", sumLedger("reserved_delta_kg", p.tankId()));
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'CONSUME'"));
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'RELEASE'"));
    }

    @Test
    void receiptRejectsActualAboveReservedAndFailureFreesKey() {
        register("TK01", "1000", "100", "500");
        TankPlanResponse p = plan("TK01", "PL-1", 1, "300");
        // 实际量大于预占：业务拒绝
        assertThrows(BusinessRejectedException.class, () -> receipt(p.planId(), "RC-1", 2, "300.001"));
        TankView view = tankService.tankView("TK01");
        assertBd("500", view.balanceKg());
        assertBd("300", view.reservedKg());
        assertEquals(2L, view.version(), "失败不得变更版本");

        // 失败不占键：同键修正参数后可正常受理
        TankReceiptResponse ok = receipt(p.planId(), "RC-1", 2, "300");
        assertEquals("EXECUTED", ok.status());
        assertBd("200", ok.balanceKg());
    }

    @Test
    void repeatReceiptDoesNotDoubleDeduct() {
        register("TK01", "1000", "100", "500");
        TankPlanResponse p = plan("TK01", "PL-1", 1, "300");
        receipt(p.planId(), "RC-1", 2, "250");

        // 不同 requestId、同量重复回执：返回首次执行结果，不重复扣量
        TankReceiptResponse again = receipt(p.planId(), "RC-2", 3, "250");
        assertEquals("EXECUTED", again.status());
        assertBd("250", again.balanceKg());
        assertEquals(3L, again.version(), "重复回执不变更版本");
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'CONSUME'"),
                "重复回执不得重复扣量");

        // 不同量重复回执：业务拒绝
        assertThrows(BusinessRejectedException.class, () -> receipt(p.planId(), "RC-3", 3, "200"));
    }

    @Test
    void receiptRejectsCancelledOrBlockedPlan() {
        register("TK01", "1000", "100", "500");
        TankPlanResponse reserved = plan("TK01", "PL-1", 1, "300");
        cancel(reserved.planId(), "CN-1", 2);
        assertThrows(BusinessRejectedException.class, () -> receipt(reserved.planId(), "RC-9", 3, "100"));

        TankPlanResponse blocked = plan("TK01", "PL-2", 3, "450");
        assertEquals("BLOCKED", blocked.status());
        assertThrows(BusinessRejectedException.class, () -> receipt(blocked.planId(), "RC-10", 4, "100"));
    }

    // ---------------------------------------------------------------- 取消

    @Test
    void cancelReleasesOnlyUnexecutedReservation() {
        register("TK01", "1000", "100", "500");
        TankPlanResponse p = plan("TK01", "PL-1", 1, "300");

        TankCancelResponse c = cancel(p.planId(), "CN-1", 2);
        assertEquals("CANCELLED", c.status());
        assertBd("300", c.releasedQtyKg());
        assertBd("500", c.balanceKg(), "取消只释放预占，不动余额");
        assertBd("0", c.reservedKg());
        assertEquals(3L, c.version());

        // 重复取消：不重复返还
        TankCancelResponse again = cancel(p.planId(), "CN-2", 3);
        assertBd("0", again.releasedQtyKg());
        assertBd("0", again.reservedKg());
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'RELEASE'"),
                "重复取消不得重复返还");
    }

    @Test
    void cancelRejectsExecutedOrBlockedPlan() {
        register("TK01", "1000", "100", "500");
        TankPlanResponse p1 = plan("TK01", "PL-1", 1, "300");
        receipt(p1.planId(), "RC-1", 2, "300");
        assertThrows(BusinessRejectedException.class, () -> cancel(p1.planId(), "CN-1", 3));

        TankPlanResponse blocked = plan("TK01", "PL-2", 3, "250");
        assertEquals("BLOCKED", blocked.status());
        assertThrows(BusinessRejectedException.class, () -> cancel(blocked.planId(), "CN-2", 4));
    }

    // ---------------------------------------------------------------- 补液

    @Test
    void refillPostsAtomicallyAndClosesOnlySatisfiableEvents() {
        register("TK01", "1000", "100", "150");
        // 两笔计划均因可用量不足被拦截，生成两个待补液事件
        TankPlanResponse p1 = plan("TK01", "PL-1", 1, "100");
        TankPlanResponse p2 = plan("TK01", "PL-2", 2, "60");
        assertEquals("BLOCKED", p1.status());
        assertEquals("BLOCKED", p2.status());

        // 补液 10：可用量 160，仅 60 的事件可满足（160−60=100 恰等安全储备）
        TankRefillResponse r1 = refill("TK01", "RF-1", 3, "BATCH-1", "10", "20.000");
        assertBd("160", r1.balanceKg());
        assertEquals(List.of(p2.refillEventId()), r1.closedEventIds(), "只关闭可满足的事件");
        assertEquals(4L, r1.version());

        // 再补 50：可用量 210，100 的事件可满足（210−100=110 ≥ 100）
        TankRefillResponse r2 = refill("TK01", "RF-2", 4, "BATCH-2", "50", "20.000");
        assertEquals(List.of(p1.refillEventId()), r2.closedEventIds());
        assertEquals(0, count("SELECT COUNT(*) FROM tank_refill_event WHERE status = 'OPEN'"));

        // 不因一次入账自动重发旧加注命令：旧计划保持 BLOCKED，无新增预占分录
        List<TankPlan> plans = tankService.plans("TK01");
        assertEquals(2, plans.size());
        assertTrue(plans.stream().allMatch(p -> "BLOCKED".equals(p.status())),
                "补液入账不得自动重发旧加注命令");
        assertEquals(0, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'RESERVE'"));
    }

    @Test
    void refillRequiresTwoDifferentOperatorsAndFailureFreesKey() {
        register("TK01", "1000", "100", "500");
        TankRefillRequest sameOperator = new TankRefillRequest("TK01", "RF-1", 1L, "BATCH-1",
                new BigDecimal("10"), new BigDecimal("20.000"), List.of("OP-A", "OP-A"), T0);
        assertThrows(BusinessRejectedException.class, () -> tankService.refill(sameOperator));
        assertBd("500", tankService.tankView("TK01").balanceKg());

        // 失败不占键：同键换成两名不同交接人后受理
        TankRefillResponse ok = refill("TK01", "RF-1", 1, "BATCH-1", "10", "20.000");
        assertBd("510", ok.balanceKg());
    }

    @Test
    void refillConcentrationClosedInterval() {
        register("TK01", "1000", "100", "100");
        // 许可闭区间 [16.000, 24.000]：恰在边界允许
        assertBd("110", refill("TK01", "RF-1", 1, "B-1", "10", "16.000").balanceKg());
        assertBd("120", refill("TK01", "RF-2", 2, "B-2", "10", "24.000").balanceKg());
        // 区间外拒绝
        assertThrows(BusinessRejectedException.class,
                () -> refill("TK01", "RF-3", 3, "B-3", "10", "15.999"));
        assertThrows(BusinessRejectedException.class,
                () -> refill("TK01", "RF-4", 3, "B-4", "10", "24.001"));
        assertBd("120", tankService.tankView("TK01").balanceKg());
    }

    @Test
    void refillCapacityBoundary() {
        register("TK01", "1000", "100", "900");
        // 恰等容量允许
        assertBd("1000", refill("TK01", "RF-1", 1, "B-1", "100", "20.000").balanceKg());
        // 超容量拒绝
        assertThrows(BusinessRejectedException.class,
                () -> refill("TK01", "RF-2", 2, "B-2", "0.001", "20.000"));
        assertBd("1000", tankService.tankView("TK01").balanceKg());
    }

    @Test
    void refillDuplicateBatchRejected() {
        register("TK01", "1000", "100", "100");
        refill("TK01", "RF-1", 1, "BATCH-1", "10", "20.000");
        // 不同 requestId 相同运输批号：唯一批号拒绝，不重复入账
        assertThrows(BusinessRejectedException.class,
                () -> refill("TK01", "RF-2", 2, "BATCH-1", "10", "20.000"));
        assertBd("110", tankService.tankView("TK01").balanceKg());
        assertEquals(1, count("SELECT COUNT(*) FROM tank_refill"));
    }

    // ---------------------------------------------------------------- 幂等与版本

    @Test
    void sameKeySameParamsReplaysFrozenResult() {
        register("TK01", "1000", "100", "100");
        TankRefillResponse first = refill("TK01", "RF-1", 1, "BATCH-1", "10", "20.000");
        TankRefillResponse replay = refill("TK01", "RF-1", 1, "BATCH-1", "10", "20.000");
        assertTrue(replay.idempotentReplay());
        assertEquals(first.refillId(), replay.refillId());
        assertBd("110", tankService.tankView("TK01").balanceKg());
        assertEquals(1, count("SELECT COUNT(*) FROM tank_refill"), "重放不得重复入账");
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'REFILL'"));
    }

    @Test
    void sameKeyDifferentParamsConflict409() {
        register("TK01", "1000", "100", "100");
        refill("TK01", "RF-1", 1, "BATCH-1", "10", "20.000");
        // 同键异参：409，且不改变任何数据
        assertThrows(IdempotentConflictException.class,
                () -> refill("TK01", "RF-1", 1, "BATCH-1", "20", "20.000"));
        assertThrows(IdempotentConflictException.class,
                () -> refill("TK01", "RF-1", 1, "BATCH-2", "10", "20.000"));
        assertBd("110", tankService.tankView("TK01").balanceKg());
        assertEquals(1, count("SELECT COUNT(*) FROM tank_refill"));
    }

    @Test
    void versionIncrementsOnEverySuccessfulChange() {
        TankRegisterResponse reg = register("TK01", "1000", "100", "500");
        assertEquals(1L, reg.version());
        TankPlanResponse p = plan("TK01", "PL-1", 1, "100");
        assertEquals(2L, p.version());
        TankReceiptResponse r = receipt(p.planId(), "RC-1", 2, "100");
        assertEquals(3L, r.version());
        TankRefillResponse f = refill("TK01", "RF-1", 3, "B-1", "50", "20.000");
        assertEquals(4L, f.version());
        // 过期版本一律冲突
        assertThrows(VersionConflictException.class, () -> plan("TK01", "PL-2", 3, "10"));
        assertEquals(4L, tankService.tankView("TK01").version());
    }

    // ---------------------------------------------------------------- 守恒与日结

    @Test
    void conservationAcrossMixedOperations() {
        TankRegisterResponse reg = register("TK01", "1000", "100", "500");
        TankPlanResponse p1 = plan("TK01", "PL-1", 1, "300");
        receipt(p1.planId(), "RC-1", 2, "250");
        TankPlanResponse p2 = plan("TK01", "PL-2", 3, "100");
        cancel(p2.planId(), "CN-1", 4);
        refill("TK01", "RF-1", 5, "B-1", "200", "20.000");

        TankView view = tankService.tankView("TK01");
        // 余额 = 500 − 250 + 200 = 450；预占 = 0
        assertBd("450", view.balanceKg());
        assertBd("0", view.reservedKg());
        // 台账勾稽：罐余额=全部余额变动之和，预占=全部预占变动之和
        assertBd("450", sumLedger("balance_delta_kg", reg.tankId()));
        assertBd("0", sumLedger("reserved_delta_kg", reg.tankId()));
        // 余额 = 期初 + 补液 − 消耗（按规范值比较，整数/小数文本等价）
        BigDecimal refilled = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity_kg),0) FROM tank_refill WHERE tank_id = ?",
                BigDecimal.class, reg.tankId());
        BigDecimal consumed = jdbc.queryForObject(
                "SELECT COALESCE(SUM(actual_qty_kg),0) FROM tank_plan WHERE tank_id = ? AND status = 'EXECUTED'",
                BigDecimal.class, reg.tankId());
        BigDecimal expected = new BigDecimal("500.000").add(refilled).subtract(consumed);
        assertEquals(0, expected.compareTo(view.balanceKg()), "余额、补液与消耗明细须守恒");
    }

    @Test
    void laterRefillDoesNotChangeSealedDailyClose() {
        // 日结封存 2026-09-26（当日无检测，上报 0，偏差 0 封存）
        DailyCloseResponse closed = dailyCloseService.close(
                new DailyCloseRequest("ST01", "DC-1", LocalDate.of(2026, 9, 26), BigDecimal.ZERO));
        assertEquals("SEALED", closed.status());

        // 后续日期补液入账
        register("TK01", "1000", "100", "100");
        TankRefillResponse refill = tankService.refill(new TankRefillRequest("TK01", "RF-1", 1L,
                "B-1", new BigDecimal("50"), new BigDecimal("20.000"),
                List.of("OP-A", "OP-B"), LocalDate.of(2026, 9, 27).atTime(9, 0)));
        assertBd("150", refill.balanceKg());

        // 旧勾稽不变：封存行保持原值
        assertEquals("SEALED", jdbc.queryForObject(
                "SELECT status FROM daily_close WHERE station_id = 1 AND biz_date = '2026-09-26'",
                String.class));
        assertBd("0", jdbc.queryForObject(
                "SELECT total_flow_m3 FROM daily_close WHERE station_id = 1 AND biz_date = '2026-09-26'",
                BigDecimal.class));
        assertBd("0.0000", jdbc.queryForObject(
                "SELECT deviation_pct FROM daily_close WHERE station_id = 1 AND biz_date = '2026-09-26'",
                BigDecimal.class));
        assertEquals(0, count("SELECT COUNT(*) FROM daily_close WHERE station_id = 1 "
                + "AND biz_date = '2026-09-27'"), "补液不得为次日生成日结行");
    }
}
