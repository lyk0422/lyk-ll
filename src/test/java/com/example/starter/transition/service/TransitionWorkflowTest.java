package com.example.starter.transition.service;

import com.example.starter.transition.AbstractIntegrationTest;
import com.example.starter.transition.ApiException;
import com.example.starter.transition.TransitionStatus;
import com.example.starter.transition.dto.ApplyOrderRequest;
import com.example.starter.transition.dto.DisposeRollsRequest;
import com.example.starter.transition.dto.ExecuteOrderRequest;
import com.example.starter.transition.dto.IdempotentResult;
import com.example.starter.transition.dto.InspectionRequest;
import com.example.starter.transition.dto.OrderView;
import com.example.starter.transition.dto.ReexamRequest;
import com.example.starter.transition.dto.RollView;
import com.example.starter.transition.dto.TransitionRecomputeView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 改品种主流程 H2 集成测试：判定边界、工艺窗口、过渡卷强制降级、复议改判一次、
 * 同键重放/异参冲突、归档终态迟到事件、过渡料复算。
 */
@Import(AbstractIntegrationTest.FixedClockConfiguration.class)
class TransitionWorkflowTest extends AbstractIntegrationTest {

    @Autowired
    private TransitionService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        cleanBusinessTables(jdbc);
    }

    @Test
    @DisplayName("恰10%直切+灰分恰2级：全流程直切，不占用清洗窗口，窗口边界参数合格")
    void directSwitchAtBoundaryFullFlow() {
        OrderView applied = service.apply(new ApplyOrderRequest(
                "req-direct-1", "PM-1", "G-A4-70", "G-A4-77")).data();
        assertEquals(TransitionStatus.APPLIED, applied.status());

        OrderView decided = service.decide(applied.orderNo());
        assertEquals(0, new BigDecimal("10.0000").compareTo(decided.basisWeightDeltaPct()));
        assertEquals(2, decided.ashLevelDelta());
        assertTrue(decided.directSwitch(), "恰 10% 直切");
        assertFalse(decided.wireFlush(), "灰分恰 2 级不冲洗网部");
        assertEquals(TransitionStatus.DECIDED, decided.status());

        // 新品种 G-A4-77 窗口车速 [820,1020]、浓度 [0.850,1.250]：恰下边界合格。
        OrderView executed = service.execute(applied.orderNo(), new ExecuteOrderRequest(
                new BigDecimal("820.000"), new BigDecimal("0.850"), 120));
        assertEquals(0, new BigDecimal("1640.000").compareTo(executed.transitionLengthM()),
                "820 m/min × 120 s / 60 = 1640 m");
        assertEquals(TransitionStatus.EXECUTED, executed.status());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM cleaning_window WHERE order_no = ?",
                Long.class, applied.orderNo()), "直切不得登记清洗窗口");

        OrderView disposed = service.dispose(applied.orderNo(), new DisposeRollsRequest(List.of(
                new DisposeRollsRequest.RollItem("R-DIRECT-1", new BigDecimal("800.000")),
                new DisposeRollsRequest.RollItem("R-DIRECT-2", new BigDecimal("840.000")))));
        assertEquals(TransitionStatus.DISPOSED, disposed.status());

        RollView roll = service.getRoll("R-DIRECT-1");
        assertEquals("DOWNGRADED", roll.verdict(), "过渡卷强制降级");
        assertEquals("NON_GENUINE", roll.warehouseStatus(), "不得按正品入库");
        assertEquals(1, roll.chain().size());
        assertEquals("FORCED_DOWNGRADE", roll.chain().get(0).eventType());

        OrderView completed = service.complete(applied.orderNo());
        assertEquals(TransitionStatus.COMPLETED, completed.status());
        // 完成后释放纸机，同机可再申请。
        OrderView next = service.apply(new ApplyOrderRequest(
                "req-direct-2", "PM-1", "G-A4-70", "G-A4-77")).data();
        assertNotNull(next.orderNo());
    }

    @Test
    @DisplayName("超10%且灰分差3级：停机清洗并冲洗网部，执行同事务登记清洗窗口，完成后释放")
    void stopCleaningFlowOccupiesCleaningWindowAtomically() {
        OrderView applied = service.apply(new ApplyOrderRequest(
                "req-clean-1", "PM-2", "G-A4-70", "G-A4-80")).data();
        OrderView decided = service.decide(applied.orderNo());
        assertFalse(decided.directSwitch(), "克重差 14.29% 须停机清洗");
        assertTrue(decided.wireFlush(), "灰分差 3 级须冲洗网部");

        // 恰在 G-A4-80 窗口上车速上边界 1030、浓度上边界 1.300，合格。
        OrderView executed = service.execute(applied.orderNo(), new ExecuteOrderRequest(
                new BigDecimal("1030.000"), new BigDecimal("1.300"), 60));
        assertEquals(0, new BigDecimal("1030.000").compareTo(executed.transitionLengthM()));

        Long activeWindows = jdbc.queryForObject(
                "SELECT COUNT(1) FROM cleaning_window WHERE order_no = ? AND active_flag = 'ACTIVE'",
                Long.class, applied.orderNo());
        assertEquals(1L, activeWindows, "停机清洗必须在同事务占用清洗窗口");
        Long activeSlots = jdbc.queryForObject(
                "SELECT COUNT(1) FROM machine_active_slot WHERE paper_machine = 'PM-2' AND active_flag = 'ACTIVE'",
                Long.class);
        assertEquals(1L, activeSlots, "执行期间纸机占用保持");

        service.dispose(applied.orderNo(), new DisposeRollsRequest(List.of(
                new DisposeRollsRequest.RollItem("R-CLEAN-1", new BigDecimal("500.000")))));
        service.complete(applied.orderNo());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM cleaning_window WHERE order_no = ? AND active_flag = 'ACTIVE'",
                Long.class, applied.orderNo()), "完成后清洗窗口释放");
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM machine_active_slot WHERE paper_machine = 'PM-2' AND active_flag = 'ACTIVE'",
                Long.class), "完成后纸机释放");
    }

    @Test
    @DisplayName("执行参数越过工艺窗口边界返回400，不产生清洗占用")
    void executeOutOfWindowRejected() {
        OrderView applied = service.apply(new ApplyOrderRequest(
                "req-window-1", "PM-3", "G-A4-70", "G-A4-80")).data();
        service.decide(applied.orderNo());
        ApiException ex = assertThrows(ApiException.class, () -> service.execute(applied.orderNo(),
                new ExecuteOrderRequest(new BigDecimal("1030.001"), new BigDecimal("1.000"), 60)));
        assertEquals(400, ex.getStatus());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM cleaning_window WHERE order_no = ?",
                Long.class, applied.orderNo()));
    }

    @Test
    @DisplayName("过渡卷强制降级；首次检验恰达门槛仅取得复议资格，复议改判正品且仅一次")
    void forcedDowngradeThenReexamUpgradesOnce() {
        OrderView order = applyDecideExecuteDispose("req-reexam-1", "PM-4",
                "R-RE-1", new BigDecimal("700.000"));

        // 恰达正品门槛的首次检验：仍强制降级。
        IdempotentResult<RollView> first = service.submitInspection(inspection("insp-1", "R-RE-1"));
        assertFalse(first.replayed());
        RollView afterFirst = first.data();
        assertTrue(afterFirst.eligibleReexam());
        assertEquals("DOWNGRADED", afterFirst.verdict(), "首次达门槛也不得直接入正品库");
        assertEquals("NON_GENUINE", afterFirst.warehouseStatus());

        // 复议改判正品一次。
        ReexamRequest reexamReq = reexam("rex-1", "R-RE-1");
        IdempotentResult<RollView> reexam = service.submitReexam(reexamReq);
        RollView upgraded = reexam.data();
        assertEquals("UPGRADED", upgraded.verdict());
        assertEquals("GENUINE", upgraded.warehouseStatus());
        assertEquals(1, upgraded.reexamCount());
        assertEquals(2, upgraded.chain().size(), "改判链含降级与改判两条");
        assertEquals("FORCED_DOWNGRADE", upgraded.chain().get(0).eventType(),
                "原降级记录保留可查");
        assertEquals("REEXAM_UPGRADED", upgraded.chain().get(1).eventType());

        // 同键同参重放复议：返回首次改判结果，不新增检验/事件。
        IdempotentResult<RollView> reexamReplay = service.submitReexam(reexamReq);
        assertTrue(reexamReplay.replayed());
        assertEquals("UPGRADED", reexamReplay.data().verdict());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_inspection WHERE roll_no = 'R-RE-1' AND inspection_type = 'REEXAM'",
                Long.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_verdict_event WHERE roll_no = 'R-RE-1' AND event_type = 'REEXAM_UPGRADED'",
                Long.class));

        // 再次复议拒绝：终态唯一。
        ApiException second = assertThrows(ApiException.class,
                () -> service.submitReexam(reexam("rex-2", "R-RE-1")));
        assertEquals(409, second.getStatus());
        assertEquals(1, service.getRoll("R-RE-1").reexamCount());

        // 复算：记录长度与公式一致，降级卷明细可查。
        TransitionRecomputeView recompute = service.recompute(order.orderNo());
        assertEquals(0, recompute.recordedTransitionLengthM().compareTo(recompute.recomputedTransitionLengthM()));
        assertEquals(0, new BigDecimal("700.000").compareTo(recompute.sumRollLengthM()));
        assertEquals(1, recompute.rolls().size());
    }

    @Test
    @DisplayName("复议指标未达门槛则驳回，终态保持降级且不得再次复议")
    void reexamBelowThresholdRejectedOnce() {
        applyDecideExecuteDispose("req-reexam-2", "PM-5", "R-RE-2", new BigDecimal("600.000"));
        service.submitInspection(inspection("insp-2", "R-RE-2"));
        IdempotentResult<RollView> rejected = service.submitReexam(new ReexamRequest(
                "rex-3", "R-RE-2", new BigDecimal("19.000"), new BigDecimal("5.000"),
                new BigDecimal("46.000"), new BigDecimal("80.00")));
        assertEquals("DOWNGRADED", rejected.data().verdict());
        assertEquals("NON_GENUINE", rejected.data().warehouseStatus());
        assertEquals(1, rejected.data().reexamCount());
        assertEquals("REEXAM_REJECTED", rejected.data().chain().get(1).eventType());
        ApiException again = assertThrows(ApiException.class,
                () -> service.submitReexam(reexam("rex-4", "R-RE-2")));
        assertEquals(409, again.getStatus());
    }

    @Test
    @DisplayName("首次检验未达门槛不得申请复议")
    void reexamRequiresInitialEligibility() {
        applyDecideExecuteDispose("req-reexam-3", "PM-6", "R-RE-3", new BigDecimal("600.000"));
        service.submitInspection(new InspectionRequest(
                "insp-3", "R-RE-3", new BigDecimal("19.500"), new BigDecimal("5.000"),
                new BigDecimal("46.000"), new BigDecimal("80.00")));
        ApiException ex = assertThrows(ApiException.class,
                () -> service.submitReexam(reexam("rex-5", "R-RE-3")));
        assertEquals(409, ex.getStatus());
    }

    @Test
    @DisplayName("申请与检验同键同参重放首次结果，同键异参返回409且不产生新记录")
    void idempotentReplayAndConflict() {
        ApplyOrderRequest applyReq = new ApplyOrderRequest(
                "idem-apply-1", "PM-7", "G-A4-70", "G-A4-77");
        IdempotentResult<OrderView> first = service.apply(applyReq);
        assertFalse(first.replayed());
        IdempotentResult<OrderView> replay = service.apply(applyReq);
        assertTrue(replay.replayed());
        assertEquals(first.data().orderNo(), replay.data().orderNo());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(1) FROM transition_order", Long.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM machine_active_slot WHERE paper_machine = 'PM-7'", Long.class));

        ApiException conflict = assertThrows(ApiException.class, () -> service.apply(
                new ApplyOrderRequest("idem-apply-1", "PM-7", "G-A4-70", "G-A4-80")));
        assertEquals(409, conflict.getStatus());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(1) FROM transition_order", Long.class),
                "异参冲突不得产生新记录");

        // 检验重放
        String orderNo = first.data().orderNo();
        service.decide(orderNo);
        service.execute(orderNo, new ExecuteOrderRequest(
                new BigDecimal("900.000"), new BigDecimal("1.000"), 60));
        service.dispose(orderNo, new DisposeRollsRequest(List.of(
                new DisposeRollsRequest.RollItem("R-IDEM-1", new BigDecimal("900.000")))));
        InspectionRequest inspReq = inspection("idem-insp-1", "R-IDEM-1");
        service.submitInspection(inspReq);
        IdempotentResult<RollView> inspReplay = service.submitInspection(inspReq);
        assertTrue(inspReplay.replayed());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_inspection WHERE roll_no = 'R-IDEM-1'", Long.class));
        ApiException inspConflict = assertThrows(ApiException.class,
                () -> service.submitInspection(new InspectionRequest(
                        "idem-insp-1", "R-IDEM-1", new BigDecimal("10.000"), new BigDecimal("5.000"),
                        new BigDecimal("46.000"), new BigDecimal("77.00"))));
        assertEquals(409, inspConflict.getStatus());
    }

    @Test
    @DisplayName("卷号唯一登记：重复卷号冲突回滚，不产生重复降级事件")
    void duplicateRollNumberRejected() {
        OrderView order1 = applyDecideExecute("dup-1", "PM-8", "G-A4-70", "G-A4-77");
        ApiException ex = assertThrows(ApiException.class, () -> service.dispose(order1.orderNo(),
                new DisposeRollsRequest(List.of(
                        new DisposeRollsRequest.RollItem("R-DUP-1", new BigDecimal("100.000")),
                        new DisposeRollsRequest.RollItem("R-DUP-1", new BigDecimal("200.000"))))));
        assertEquals(409, ex.getStatus());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM transition_roll WHERE roll_no = 'R-DUP-1'", Long.class));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_verdict_event WHERE roll_no = 'R-DUP-1'", Long.class));
    }

    @Test
    @DisplayName("归档为终态：重复归档幂等成功；归档后迟到检验只记迟到事件，终态不变")
    void archiveTerminalAndLateInspection() {
        OrderView order = applyDecideExecuteDispose("arch-1", "PM-9",
                "R-ARCH-1", new BigDecimal("400.000"));
        service.complete(order.orderNo());
        OrderView archived = service.archive(order.orderNo());
        assertEquals(TransitionStatus.ARCHIVED, archived.status());
        OrderView again = service.archive(order.orderNo());
        assertEquals(TransitionStatus.ARCHIVED, again.status(), "重复归档幂等成功");

        // 归档后到达的首次检验：只记迟到事件。
        IdempotentResult<RollView> late = service.submitInspection(inspection("late-insp-1", "R-ARCH-1"));
        assertEquals("DOWNGRADED", late.data().verdict());
        assertFalse(late.data().eligibleReexam(), "迟到检验不得改变复议资格");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM late_event WHERE order_no = ? AND event_type = 'LATE_INSPECTION'",
                Long.class, order.orderNo()));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_inspection WHERE roll_no = 'R-ARCH-1'", Long.class),
                "迟到检验不得写入检验记录");

        // 迟到请求重放不重复登记迟到事件。
        service.submitInspection(inspection("late-insp-1", "R-ARCH-1"));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM late_event WHERE order_no = ? AND event_type = 'LATE_INSPECTION'",
                Long.class, order.orderNo()));

        // 归档后迟到复议同样只记事件，终态仍为降级。
        service.submitReexam(reexam("late-rex-1", "R-ARCH-1"));
        assertEquals("DOWNGRADED", service.getRoll("R-ARCH-1").verdict());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM late_event WHERE roll_no = 'R-ARCH-1' AND event_type = 'LATE_REEXAM'",
                Long.class));
    }

    @Test
    @DisplayName("同一纸机在途唯一：未完成时第二张申请409，释放后可申请")
    void singleActiveOrderPerMachine() {
        service.apply(new ApplyOrderRequest("slot-1", "PM-10", "G-A4-70", "G-A4-77"));
        ApiException ex = assertThrows(ApiException.class, () -> service.apply(
                new ApplyOrderRequest("slot-2", "PM-10", "G-A4-70", "G-A4-80")));
        assertEquals(409, ex.getStatus());
        // 不同纸机不受影响。
        assertNotNull(service.apply(new ApplyOrderRequest(
                "slot-3", "PM-11", "G-A4-70", "G-A4-77")).data());
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    private OrderView applyDecideExecute(String requestId, String machine, String from, String to) {
        OrderView applied = service.apply(new ApplyOrderRequest(requestId, machine, from, to)).data();
        service.decide(applied.orderNo());
        OrderView decided = service.getOrder(applied.orderNo());
        BigDecimal speed = to.equals("G-A4-77") ? new BigDecimal("820.000") : new BigDecimal("830.000");
        BigDecimal consistency = to.equals("G-A4-77") ? new BigDecimal("0.850") : new BigDecimal("0.900");
        service.execute(applied.orderNo(), new ExecuteOrderRequest(speed, consistency, 60));
        return service.getOrder(applied.orderNo());
    }

    private OrderView applyDecideExecuteDispose(String requestId, String machine,
                                                String rollNo, BigDecimal length) {
        OrderView executed = applyDecideExecute(requestId, machine, "G-A4-70", "G-A4-77");
        service.dispose(executed.orderNo(), new DisposeRollsRequest(List.of(
                new DisposeRollsRequest.RollItem(rollNo, length))));
        return service.getOrder(executed.orderNo());
    }

    /** 恰达全部正品门槛的首次检验（新品种 G-A4-77，标称 77g）。 */
    private InspectionRequest inspection(String requestId, String rollNo) {
        return new InspectionRequest(requestId, rollNo,
                new BigDecimal("18.000"), new BigDecimal("4.000"),
                new BigDecimal("45.000"), new BigDecimal("77.00"));
    }

    private ReexamRequest reexam(String requestId, String rollNo) {
        return new ReexamRequest(requestId, rollNo,
                new BigDecimal("17.000"), new BigDecimal("6.000"),
                new BigDecimal("47.000"), new BigDecimal("77.00"));
    }
}
