package com.example.starter.transition.service;

import com.example.starter.transition.AbstractIntegrationTest;
import com.example.starter.transition.ApiException;
import com.example.starter.transition.TransitionStatus;
import com.example.starter.transition.dto.ApplyOrderRequest;
import com.example.starter.transition.dto.BlendAdjustRequest;
import com.example.starter.transition.dto.BlendControlStateView;
import com.example.starter.transition.dto.BlendReadingRequest;
import com.example.starter.transition.dto.BlendReadingView;
import com.example.starter.transition.dto.BlendRatioVersionView;
import com.example.starter.transition.dto.BlendRecomputeView;
import com.example.starter.transition.dto.BlendResumeRequest;
import com.example.starter.transition.dto.DisposeRollsRequest;
import com.example.starter.transition.dto.ExecuteOrderRequest;
import com.example.starter.transition.dto.IdempotentResult;
import com.example.starter.transition.dto.OrderView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回抄浆掺配比例 H2 集成测试：边界比例申请、执行中调整与判定同事务落库、
 * 读数去重、连续超差失控暂停转人工、复核恢复、幂等重放/异参冲突、版本链复算与回滚。
 */
@Import(AbstractIntegrationTest.FixedClockConfiguration.class)
class BlendWorkflowTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 25, 10, 0, 0);

    @Autowired
    private TransitionService transitionService;
    @Autowired
    private BlendService blendService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        cleanBusinessTables(jdbc);
    }

    @Test
    @DisplayName("申请携带恰达区间边界的掺配比例（5.000/30.000）：受理并同事务落库初始版本与控制状态")
    void applyWithBoundaryRatioPersistsInitialVersion() {
        OrderView lower = transitionService.apply(new ApplyOrderRequest(
                "blend-apply-low", "PM-B1", "G-A4-70", "G-A4-77", new BigDecimal("5.000"))).data();
        assertEquals(TransitionStatus.APPLIED, lower.status());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_ratio_version WHERE order_no = ? AND version_no = 1"
                        + " AND target_ratio_pct = 5.000 AND adjust_mode = 'INITIAL' AND decided_at IS NOT NULL",
                Long.class, lower.orderNo()), "初始比例版本与申请同事务落库");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_control_state WHERE order_no = ?"
                        + " AND control_status = 'RUNNING' AND current_version_no = 1 AND out_streak = 0",
                Long.class, lower.orderNo()));

        OrderView upper = transitionService.apply(new ApplyOrderRequest(
                "blend-apply-high", "PM-B2", "G-A4-70", "G-A4-77", new BigDecimal("30.000"))).data();
        assertNotNull(upper.orderNo());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_ratio_version WHERE order_no = ? AND target_ratio_pct = 30.000",
                Long.class, upper.orderNo()), "上边界 30.000 合法");
    }

    @Test
    @DisplayName("申请比例越界（4.999/30.001）返回400并整体回滚：无订单、无版本、无幂等记录")
    void applyWithOutOfRangeRatioRejectedAndRolledBack() {
        ApiException low = assertThrows(ApiException.class, () -> transitionService.apply(
                new ApplyOrderRequest("blend-apply-oor-1", "PM-B3", "G-A4-70", "G-A4-77",
                        new BigDecimal("4.999"))));
        assertEquals(400, low.getStatus());
        ApiException high = assertThrows(ApiException.class, () -> transitionService.apply(
                new ApplyOrderRequest("blend-apply-oor-2", "PM-B3", "G-A4-70", "G-A4-77",
                        new BigDecimal("30.001"))));
        assertEquals(400, high.getStatus());

        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(1) FROM transition_order", Long.class),
                "越界申请回滚，不留订单");
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(1) FROM blend_ratio_version", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(1) FROM blend_control_state", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(1) FROM idempotent_request", Long.class),
                "失败事务回滚，不留幂等占位");
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(1) FROM machine_active_slot", Long.class),
                "回滚后纸机占用不残留");
    }

    @Test
    @DisplayName("执行中调整：差值恰5%在线直调、超5%降速调整，判定结论与比例版本同事务落库，版本链连续")
    void adjustRatioDecisionPersistedWithVersionAtomically() {
        String orderNo = applyDecideExecuteWithRatio("blend-adj-1", "PM-B4", "20.000");

        // 20.000 → 25.000：差值恰 5.0000%，在线直调。
        IdempotentResult<BlendRatioVersionView> direct = blendService.adjustRatio(orderNo,
                new BlendAdjustRequest("blend-adj-1-v2", new BigDecimal("25.000")));
        assertFalse(direct.replayed());
        assertEquals(2, direct.data().versionNo());
        assertEquals("DIRECT_ADJUST", direct.data().adjustMode());
        assertEquals(0, new BigDecimal("5.0000").compareTo(direct.data().ratioDeltaPct()));

        // 25.000 → 12.000：差值 13%，降速调整。
        IdempotentResult<BlendRatioVersionView> speedDown = blendService.adjustRatio(orderNo,
                new BlendAdjustRequest("blend-adj-1-v3", new BigDecimal("12.000")));
        assertEquals(3, speedDown.data().versionNo());
        assertEquals("SPEED_DOWN_ADJUST", speedDown.data().adjustMode());
        assertEquals(0, new BigDecimal("13.0000").compareTo(speedDown.data().ratioDeltaPct()));

        // 判定结论与版本同事务落库：版本链 1→2→3 完整，当前版本前进到 3。
        assertEquals(3L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_ratio_version WHERE order_no = ? AND decided_at IS NOT NULL",
                Long.class, orderNo));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_control_state WHERE order_no = ? AND current_version_no = 3",
                Long.class, orderNo));
    }

    @Test
    @DisplayName("调整比例越界返回400并回滚：版本链与控制状态不变")
    void adjustOutOfRangeRejectedAndRolledBack() {
        String orderNo = applyDecideExecuteWithRatio("blend-adj-2", "PM-B5", "20.000");
        ApiException ex = assertThrows(ApiException.class, () -> blendService.adjustRatio(orderNo,
                new BlendAdjustRequest("blend-adj-2-oor", new BigDecimal("30.001"))));
        assertEquals(400, ex.getStatus());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_ratio_version WHERE order_no = ?", Long.class, orderNo),
                "越界调整回滚，不产生新版本");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_control_state WHERE order_no = ? AND current_version_no = 1",
                Long.class, orderNo));
    }

    @Test
    @DisplayName("非执行阶段或未设比例的订单：调整与读数拒绝（409）")
    void blendOpsRequireExecutedOrderWithRatio() {
        // 已申请未执行：不得调整。
        OrderView applied = transitionService.apply(new ApplyOrderRequest(
                "blend-stage-1", "PM-B6", "G-A4-70", "G-A4-77", new BigDecimal("20.000"))).data();
        ApiException notExecuted = assertThrows(ApiException.class, () -> blendService.adjustRatio(
                applied.orderNo(), new BlendAdjustRequest("blend-stage-1-adj", new BigDecimal("21.000"))));
        assertEquals(409, notExecuted.getStatus());

        // 未设掺配比例的订单：执行后也不得调整或上报读数。
        OrderView noRatio = transitionService.apply(new ApplyOrderRequest(
                "blend-stage-2", "PM-B7", "G-A4-70", "G-A4-77")).data();
        transitionService.decide(noRatio.orderNo());
        transitionService.execute(noRatio.orderNo(), new ExecuteOrderRequest(
                new BigDecimal("820.000"), new BigDecimal("0.850"), 120));
        ApiException noRatioAdjust = assertThrows(ApiException.class, () -> blendService.adjustRatio(
                noRatio.orderNo(), new BlendAdjustRequest("blend-stage-2-adj", new BigDecimal("20.000"))));
        assertEquals(409, noRatioAdjust.getStatus());
        ApiException noRatioReading = assertThrows(ApiException.class, () -> blendService.submitReading(
                noRatio.orderNo(), new BlendReadingRequest("blend-stage-2-rd", T0, new BigDecimal("20.000"))));
        assertEquals(409, noRatioReading.getStatus());
    }

    @Test
    @DisplayName("实际掺配读数按订单与时刻去重：同时刻不同requestId冲突409，读数唯一")
    void readingsDeduplicatedByOrderAndTime() {
        String orderNo = applyDecideExecuteWithRatio("blend-dedup-1", "PM-B8", "20.000");
        blendService.submitReading(orderNo, new BlendReadingRequest(
                "blend-dedup-1-r1", T0, new BigDecimal("20.500")));
        ApiException ex = assertThrows(ApiException.class, () -> blendService.submitReading(orderNo,
                new BlendReadingRequest("blend-dedup-1-r2", T0, new BigDecimal("21.500"))));
        assertEquals(409, ex.getStatus(), "同一订单同时刻只允许一条读数");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_reading WHERE order_no = ?", Long.class, orderNo));
        // 不同时刻可继续登记。
        blendService.submitReading(orderNo, new BlendReadingRequest(
                "blend-dedup-1-r3", T0.plusMinutes(1), new BigDecimal("20.500")));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_reading WHERE order_no = ?", Long.class, orderNo));
    }

    @Test
    @DisplayName("恰2%合格；连续3点超差判失控即暂停转人工：读数/调整/处置全部阻断，复核恢复后放行")
    void outOfControlPausesExecutionUntilReviewedResume() {
        String orderNo = applyDecideExecuteWithRatio("blend-ooc-1", "PM-B9", "20.000");

        // 恰 2% 偏差合格，不计超差。
        BlendReadingView atBoundary = blendService.submitReading(orderNo, new BlendReadingRequest(
                "blend-ooc-1-r1", T0, new BigDecimal("22.000"))).data();
        assertTrue(atBoundary.withinTolerance(), "恰 2% 合格");
        assertFalse(atBoundary.outOfControl());

        // 两个超差点后插入合格点，连击清零。
        blendService.submitReading(orderNo, new BlendReadingRequest(
                "blend-ooc-1-r2", T0.plusMinutes(1), new BigDecimal("22.001")));
        blendService.submitReading(orderNo, new BlendReadingRequest(
                "blend-ooc-1-r3", T0.plusMinutes(2), new BigDecimal("22.002")));
        BlendReadingView reset = blendService.submitReading(orderNo, new BlendReadingRequest(
                "blend-ooc-1-r4", T0.plusMinutes(3), new BigDecimal("20.000"))).data();
        assertFalse(reset.outOfControl(), "合格点清零连击，不判失控");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_control_state WHERE order_no = ? AND out_streak = 0",
                Long.class, orderNo));

        // 连续 3 个超差点：第 3 点判失控，暂停转人工。
        blendService.submitReading(orderNo, new BlendReadingRequest(
                "blend-ooc-1-r5", T0.plusMinutes(4), new BigDecimal("22.001")));
        blendService.submitReading(orderNo, new BlendReadingRequest(
                "blend-ooc-1-r6", T0.plusMinutes(5), new BigDecimal("22.002")));
        BlendReadingView third = blendService.submitReading(orderNo, new BlendReadingRequest(
                "blend-ooc-1-r7", T0.plusMinutes(6), new BigDecimal("22.003"))).data();
        assertTrue(third.outOfControl(), "连续 3 点超差判失控");
        assertEquals("PAUSED_MANUAL", third.controlStatus());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_control_event WHERE order_no = ? AND event_type = 'OUT_OF_CONTROL'",
                Long.class, orderNo), "失控事件留痕");

        // 暂停期间：读数、调整、过渡卷处置全部阻断。
        ApiException readingBlocked = assertThrows(ApiException.class, () -> blendService.submitReading(
                orderNo, new BlendReadingRequest("blend-ooc-1-r8", T0.plusMinutes(7),
                        new BigDecimal("20.000"))));
        assertEquals(409, readingBlocked.getStatus());
        ApiException adjustBlocked = assertThrows(ApiException.class, () -> blendService.adjustRatio(
                orderNo, new BlendAdjustRequest("blend-ooc-1-adj", new BigDecimal("21.000"))));
        assertEquals(409, adjustBlocked.getStatus());
        ApiException disposeBlocked = assertThrows(ApiException.class, () -> transitionService.dispose(
                orderNo, new DisposeRollsRequest(List.of(
                        new DisposeRollsRequest.RollItem("R-BLEND-1", new BigDecimal("100.000"))))));
        assertEquals(409, disposeBlocked.getStatus(), "失控暂停期间不得处置过渡卷");
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM transition_roll WHERE order_no = ?", Long.class, orderNo),
                "阻断处置回滚，不产生纸卷记录");

        // 复核确认恢复：状态回到执行中，连击清零，恢复事件留痕含复核人。
        IdempotentResult<BlendControlStateView> resumed = blendService.resume(orderNo,
                new BlendResumeRequest("blend-ooc-1-resume", "checker-li"));
        assertEquals("RUNNING", resumed.data().controlStatus());
        assertEquals(0, resumed.data().outStreak());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_control_event WHERE order_no = ?"
                        + " AND event_type = 'RESUMED' AND reviewer = 'checker-li'",
                Long.class, orderNo));

        // 恢复后读数与处置放行。
        blendService.submitReading(orderNo, new BlendReadingRequest(
                "blend-ooc-1-r9", T0.plusMinutes(8), new BigDecimal("20.100")));
        OrderView disposed = transitionService.dispose(orderNo, new DisposeRollsRequest(List.of(
                new DisposeRollsRequest.RollItem("R-BLEND-1", new BigDecimal("100.000")))));
        assertEquals(TransitionStatus.DISPOSED, disposed.status());

        // 未暂停时恢复：409。
        ApiException resumeAgain = assertThrows(ApiException.class, () -> blendService.resume(orderNo,
                new BlendResumeRequest("blend-ooc-1-resume-2", "checker-li")));
        assertEquals(409, resumeAgain.getStatus());
    }

    @Test
    @DisplayName("掺配写操作幂等：同键同参重放首次结果，同键异参409，不产生重复版本/读数/事件")
    void blendWritesIdempotentReplayAndConflict() {
        String orderNo = applyDecideExecuteWithRatio("blend-idem-1", "PM-B10", "20.000");

        // 调整幂等。
        BlendAdjustRequest adjustReq = new BlendAdjustRequest("blend-idem-1-adj", new BigDecimal("24.000"));
        IdempotentResult<BlendRatioVersionView> first = blendService.adjustRatio(orderNo, adjustReq);
        IdempotentResult<BlendRatioVersionView> replay = blendService.adjustRatio(orderNo, adjustReq);
        assertTrue(replay.replayed());
        assertEquals(first.data().versionNo(), replay.data().versionNo());
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_ratio_version WHERE order_no = ?", Long.class, orderNo),
                "重放不产生新版本");
        ApiException adjustConflict = assertThrows(ApiException.class, () -> blendService.adjustRatio(
                orderNo, new BlendAdjustRequest("blend-idem-1-adj", new BigDecimal("25.000"))));
        assertEquals(409, adjustConflict.getStatus(), "同键异参409");

        // 读数幂等。
        BlendReadingRequest readingReq = new BlendReadingRequest(
                "blend-idem-1-rd", T0, new BigDecimal("24.500"));
        blendService.submitReading(orderNo, readingReq);
        IdempotentResult<BlendReadingView> readingReplay = blendService.submitReading(orderNo, readingReq);
        assertTrue(readingReplay.replayed());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_reading WHERE order_no = ?", Long.class, orderNo));
        ApiException readingConflict = assertThrows(ApiException.class, () -> blendService.submitReading(
                orderNo, new BlendReadingRequest("blend-idem-1-rd", T0.plusMinutes(1),
                        new BigDecimal("24.500"))));
        assertEquals(409, readingConflict.getStatus());

        // 恢复幂等：先造失控，再同键重放恢复。
        blendService.submitReading(orderNo, new BlendReadingRequest(
                "blend-idem-1-o1", T0.plusMinutes(2), new BigDecimal("27.000")));
        blendService.submitReading(orderNo, new BlendReadingRequest(
                "blend-idem-1-o2", T0.plusMinutes(3), new BigDecimal("27.000")));
        blendService.submitReading(orderNo, new BlendReadingRequest(
                "blend-idem-1-o3", T0.plusMinutes(4), new BigDecimal("27.000")));
        assertEquals("PAUSED_MANUAL", blendService.getControlState(orderNo).controlStatus());
        BlendResumeRequest resumeReq = new BlendResumeRequest("blend-idem-1-resume", "checker-wang");
        blendService.resume(orderNo, resumeReq);
        IdempotentResult<BlendControlStateView> resumeReplay = blendService.resume(orderNo, resumeReq);
        assertTrue(resumeReplay.replayed());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_control_event WHERE order_no = ? AND event_type = 'RESUMED'",
                Long.class, orderNo), "重放不重复登记恢复事件");
        ApiException resumeConflict = assertThrows(ApiException.class, () -> blendService.resume(
                orderNo, new BlendResumeRequest("blend-idem-1-resume", "checker-other")));
        assertEquals(409, resumeConflict.getStatus());
    }

    @Test
    @DisplayName("版本链复算：归档历史订单按版本复算实际掺配轨迹，偏差与失控点实时重算")
    void recomputeBlendTrajectoryByVersionForArchivedOrder() {
        String orderNo = applyDecideExecuteWithRatio("blend-recomp-1", "PM-B11", "20.000");
        // 版本 1：两个读数，恰 2% 合格 + 超差一点。
        blendService.submitReading(orderNo, new BlendReadingRequest(
                "blend-recomp-1-r1", T0, new BigDecimal("22.000")));
        blendService.submitReading(orderNo, new BlendReadingRequest(
                "blend-recomp-1-r2", T0.plusMinutes(1), new BigDecimal("22.001")));
        // 换版到 25.000（恰 5% 直调），版本 2 连续 3 点超差失控。
        blendService.adjustRatio(orderNo, new BlendAdjustRequest("blend-recomp-1-adj",
                new BigDecimal("25.000")));
        blendService.submitReading(orderNo, new BlendReadingRequest(
                "blend-recomp-1-r3", T0.plusMinutes(2), new BigDecimal("28.000")));
        blendService.submitReading(orderNo, new BlendReadingRequest(
                "blend-recomp-1-r4", T0.plusMinutes(3), new BigDecimal("28.000")));
        blendService.submitReading(orderNo, new BlendReadingRequest(
                "blend-recomp-1-r5", T0.plusMinutes(4), new BigDecimal("28.000")));
        blendService.resume(orderNo, new BlendResumeRequest("blend-recomp-1-resume", "checker-zhao"));
        // 走完全流程至归档（终态）。
        transitionService.dispose(orderNo, new DisposeRollsRequest(List.of(
                new DisposeRollsRequest.RollItem("R-RECOMP-1", new BigDecimal("100.000")))));
        transitionService.complete(orderNo);
        transitionService.archive(orderNo);

        BlendRecomputeView view = blendService.recompute(orderNo);
        assertEquals(2, view.versions().size(), "版本链完整可复算");

        BlendRecomputeView.VersionTrajectory v1 = view.versions().get(0);
        assertEquals(1, v1.versionNo());
        assertEquals(0, new BigDecimal("20.000").compareTo(v1.targetRatioPct()));
        assertEquals(2, v1.readings().size());
        assertEquals(0, new BigDecimal("2.0000").compareTo(v1.readings().get(0).deviationPct()));
        assertTrue(v1.readings().get(0).withinTolerance(), "恰 2% 合格");
        assertFalse(v1.readings().get(1).withinTolerance());
        assertFalse(v1.outOfControl(), "版本 1 未达连续 3 点超差");

        BlendRecomputeView.VersionTrajectory v2 = view.versions().get(1);
        assertEquals(2, v2.versionNo());
        assertEquals("DIRECT_ADJUST", v2.adjustMode());
        assertEquals(3, v2.readings().size());
        assertEquals(0, new BigDecimal("3.0000").compareTo(v2.readings().get(0).deviationPct()));
        assertTrue(v2.outOfControl(), "版本 2 连续 3 点超差，复算判失控");

        assertEquals(2, view.events().size());
        assertEquals("OUT_OF_CONTROL", view.events().get(0).eventType());
        assertEquals("RESUMED", view.events().get(1).eventType());
        assertEquals("checker-zhao", view.events().get(1).reviewer());
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    /** 申请（携带比例）→判定→执行，返回单号；G-A4-77 窗口下边界参数合格。 */
    private String applyDecideExecuteWithRatio(String requestId, String machine, String ratio) {
        OrderView applied = transitionService.apply(new ApplyOrderRequest(
                requestId, machine, "G-A4-70", "G-A4-77", new BigDecimal(ratio))).data();
        transitionService.decide(applied.orderNo());
        transitionService.execute(applied.orderNo(), new ExecuteOrderRequest(
                new BigDecimal("820.000"), new BigDecimal("0.850"), 120));
        return applied.orderNo();
    }
}
