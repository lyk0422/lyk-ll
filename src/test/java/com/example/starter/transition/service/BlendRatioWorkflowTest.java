package com.example.starter.transition.service;

import com.example.starter.transition.AbstractIntegrationTest;
import com.example.starter.transition.ApiException;
import com.example.starter.transition.TransitionStatus;
import com.example.starter.transition.dto.AdjustBlendRatioRequest;
import com.example.starter.transition.dto.ApplyOrderRequest;
import com.example.starter.transition.dto.BlendReadingRequest;
import com.example.starter.transition.dto.BlendReadingView;
import com.example.starter.transition.dto.BlendRecomputeView;
import com.example.starter.transition.dto.BlendVersionView;
import com.example.starter.transition.dto.DisposeRollsRequest;
import com.example.starter.transition.dto.ExecuteOrderRequest;
import com.example.starter.transition.dto.IdempotentResult;
import com.example.starter.transition.dto.InspectionRequest;
import com.example.starter.transition.dto.OrderView;
import com.example.starter.transition.dto.ResumeExecutionRequest;
import com.example.starter.transition.dto.RollView;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回抄浆掺配比例 H2 集成测试：申请携带比例与判定同事务落库、执行中调整、
 * 读数去重与失控暂停转人工、复核恢复、版本链复算、幂等与回滚。
 */
@Import(AbstractIntegrationTest.FixedClockConfiguration.class)
class BlendRatioWorkflowTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 25, 10, 0, 0);

    @Autowired
    private TransitionService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        cleanBusinessTables(jdbc);
    }

    @Test
    @DisplayName("申请携带恰达边界比例（25.000%/5.000%）：判定结论与比例版本同事务落库")
    void applyWithRatioAtBoundaryDecidesAtomically() {
        // 恰上边界 25.000% 合法
        OrderView upper = service.apply(new ApplyOrderRequest(
                "blend-b1", "PM-B1", "G-A4-70", "G-A4-77", new BigDecimal("25.000"))).data();
        assertEquals(TransitionStatus.DECIDED, upper.status(), "携带比例的申请须重新走方式判定");
        assertEquals(0, new BigDecimal("10.0000").compareTo(upper.basisWeightDeltaPct()));
        assertTrue(upper.directSwitch());
        assertEquals(1, upper.currentBlendVersion());
        List<BlendVersionView> versions = service.listBlendVersions(upper.orderNo());
        assertEquals(1, versions.size());
        assertEquals(0, new BigDecimal("25.000").compareTo(versions.get(0).targetRatioPct()));
        assertEquals("APPLY", versions.get(0).source());

        // 恰下边界 5.000% 合法
        OrderView lower = service.apply(new ApplyOrderRequest(
                "blend-b2", "PM-B2", "G-A4-70", "G-A4-77", new BigDecimal("5.000"))).data();
        assertEquals(TransitionStatus.DECIDED, lower.status());
        assertEquals(0, new BigDecimal("5.000").compareTo(
                service.listBlendVersions(lower.orderNo()).get(0).targetRatioPct()));
    }

    @Test
    @DisplayName("申请比例越界返回400；判定失败时比例版本随事务回滚不留痕")
    void applyWithRatioOutOfIntervalAndRollback() {
        OrderView applied = service.apply(new ApplyOrderRequest(
                "blend-r0", "PM-B3", "G-A4-70", "G-A4-77")).data();
        // 同一纸机再申请会 409，先完成该单释放纸机不必要——直接用新纸机验证越界。
        ApiException ex = assertThrows(ApiException.class, () -> service.apply(new ApplyOrderRequest(
                "blend-r1", "PM-B4", "G-A4-70", "G-A4-77", new BigDecimal("25.001"))));
        assertEquals(400, ex.getStatus());
        ApiException ex2 = assertThrows(ApiException.class, () -> service.apply(new ApplyOrderRequest(
                "blend-r2", "PM-B4", "G-A4-70", "G-A4-77", new BigDecimal("4.999"))));
        assertEquals(400, ex2.getStatus());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_ratio_version", Long.class), "越界拒绝不得产生版本");
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM transition_order WHERE paper_machine = 'PM-B4'", Long.class));

        // 携带比例但新品种不存在：判定无法落库，版本与占用随事务整体回滚。
        ApiException ex3 = assertThrows(ApiException.class, () -> service.apply(new ApplyOrderRequest(
                "blend-r3", "PM-B5", "G-A4-70", "G-NOPE", new BigDecimal("20.000"))));
        assertEquals(400, ex3.getStatus());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_ratio_version", Long.class));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM machine_active_slot WHERE paper_machine = 'PM-B5' AND active_flag = 'ACTIVE'",
                Long.class), "回滚后不得残留纸机占用");
        assertEquals(TransitionStatus.APPLIED, applied.status());
    }

    @Test
    @DisplayName("偏离恰2%合格；连续3个超差点判失控即暂停转人工；复核确认后恢复")
    void outOfControlPausesAndResumeRequiresReview() {
        OrderView order = applyWithRatioAndExecute("blend-c1", "PM-B6", "25.000");

        // 恰 2% 偏离合格，不计超差
        IdempotentResult<BlendReadingView> ok =
                service.submitBlendReading(reading("rd-1", order.orderNo(), T0, "27.000"));
        assertFalse(ok.data().outOfTolerance(), "恰 2% 合格");
        assertEquals(0, service.getOrder(order.orderNo()).consecutiveOutOfTolerance());

        // 第 1、2 个超差点：仍执行中
        service.submitBlendReading(reading("rd-2", order.orderNo(), T0.plusMinutes(1), "28.000"));
        service.submitBlendReading(reading("rd-3", order.orderNo(), T0.plusMinutes(2), "22.900"));
        assertEquals(TransitionStatus.EXECUTED, service.getOrder(order.orderNo()).status());
        assertEquals(2, service.getOrder(order.orderNo()).consecutiveOutOfTolerance());

        // 第 3 个连续超差点：失控，同事务暂停转人工
        service.submitBlendReading(reading("rd-4", order.orderNo(), T0.plusMinutes(3), "28.500"));
        OrderView paused = service.getOrder(order.orderNo());
        assertEquals(TransitionStatus.PAUSED, paused.status(), "连续 3 个超差点判失控即暂停");
        assertEquals(3, paused.consecutiveOutOfTolerance());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_control_event WHERE order_no = ? AND event_type = 'OUT_OF_CONTROL_PAUSE'",
                Long.class, order.orderNo()));

        // 暂停后读数与处置均拒绝（转人工，须先复核恢复）
        ApiException readingWhilePaused = assertThrows(ApiException.class, () ->
                service.submitBlendReading(reading("rd-5", order.orderNo(), T0.plusMinutes(4), "25.000")));
        assertEquals(409, readingWhilePaused.getStatus());
        ApiException disposeWhilePaused = assertThrows(ApiException.class, () ->
                service.dispose(order.orderNo(), new DisposeRollsRequest(List.of(
                        new DisposeRollsRequest.RollItem("R-BL-1", new BigDecimal("100.000"))))));
        assertEquals(409, disposeWhilePaused.getStatus());

        // 未经复核确认不得恢复
        ApiException notConfirmed = assertThrows(ApiException.class, () ->
                service.resumeExecution(order.orderNo(),
                        new ResumeExecutionRequest("rs-0", "checker-1", false)));
        assertEquals(400, notConfirmed.getStatus());
        assertEquals(TransitionStatus.PAUSED, service.getOrder(order.orderNo()).status());

        // 复核确认恢复：回到执行中，超差计数清零，留恢复事件
        IdempotentResult<OrderView> resumed = service.resumeExecution(order.orderNo(),
                new ResumeExecutionRequest("rs-1", "checker-1", true));
        assertEquals(TransitionStatus.EXECUTED, resumed.data().status());
        assertEquals(0, resumed.data().consecutiveOutOfTolerance());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_control_event WHERE order_no = ? AND event_type = 'MANUAL_RESUME'",
                Long.class, order.orderNo()));

        // 恢复幂等：同键同参重放首次结果，不重复登记事件
        IdempotentResult<OrderView> replay = service.resumeExecution(order.orderNo(),
                new ResumeExecutionRequest("rs-1", "checker-1", true));
        assertTrue(replay.replayed());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_control_event WHERE order_no = ? AND event_type = 'MANUAL_RESUME'",
                Long.class, order.orderNo()));

        // 恢复后读数重新累计：两次超差不暂停
        service.submitBlendReading(reading("rd-6", order.orderNo(), T0.plusMinutes(5), "28.000"));
        service.submitBlendReading(reading("rd-7", order.orderNo(), T0.plusMinutes(6), "28.000"));
        assertEquals(TransitionStatus.EXECUTED, service.getOrder(order.orderNo()).status());
    }

    @Test
    @DisplayName("读数按订单与采样时刻去重：同时刻异参409；同键同参重放，同键异参409")
    void readingDedupAndIdempotency() {
        OrderView executed = applyWithRatioAndExecute("blend-d2", "PM-B8", "20.000");
        String orderNo = executed.orderNo();

        BlendReadingRequest first = reading("rd-d1", orderNo, T0, "21.000");
        IdempotentResult<BlendReadingView> created =
                service.submitBlendReading(first);
        assertFalse(created.replayed());
        assertEquals(0, new BigDecimal("1.000").compareTo(created.data().deviationPct()));

        // 同键同参重放
        IdempotentResult<BlendReadingView> replayed =
                service.submitBlendReading(first);
        assertTrue(replayed.replayed());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_reading WHERE order_no = ?", Long.class, orderNo));

        // 同键异参 409
        ApiException conflict = assertThrows(ApiException.class, () ->
                service.submitBlendReading(reading("rd-d1", orderNo, T0, "22.000")));
        assertEquals(409, conflict.getStatus());

        // 同一订单同一采样时刻（不同 requestId）去重冲突 409
        ApiException dupTime = assertThrows(ApiException.class, () ->
                service.submitBlendReading(reading("rd-d2", orderNo, T0, "19.000")));
        assertEquals(409, dupTime.getStatus());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_reading WHERE order_no = ?", Long.class, orderNo),
                "去重冲突不得产生第二条读数");
    }

    @Test
    @DisplayName("执行中调整生成版本链；同时刻只允许一次调整；调整后超差计数清零；版本链可复算")
    void adjustCreatesVersionChainAndResetsStreak() {
        OrderView order = applyWithRatioAndExecute("blend-e1", "PM-B9", "25.000");
        String orderNo = order.orderNo();

        // 先累计 1 个超差点
        service.submitBlendReading(reading("rd-e1", orderNo, T0, "28.000"));
        assertEquals(1, service.getOrder(orderNo).consecutiveOutOfTolerance());

        // 执行中调整：生成 v2，超差计数清零
        IdempotentResult<BlendVersionView> adjusted = service.adjustBlendRatio(orderNo,
                new AdjustBlendRatioRequest("adj-1", new BigDecimal("20.000")));
        assertFalse(adjusted.replayed());
        assertEquals(2, adjusted.data().versionNo());
        assertEquals("ADJUST", adjusted.data().source());
        assertEquals(0, service.getOrder(orderNo).consecutiveOutOfTolerance());
        assertEquals(2, service.getOrder(orderNo).currentBlendVersion());

        // 同键同参重放；同键异参 409
        assertTrue(service.adjustBlendRatio(orderNo,
                new AdjustBlendRatioRequest("adj-1", new BigDecimal("20.000"))).replayed());
        ApiException conflict = assertThrows(ApiException.class, () ->
                service.adjustBlendRatio(orderNo, new AdjustBlendRatioRequest("adj-1", new BigDecimal("21.000"))));
        assertEquals(409, conflict.getStatus());

        // 同一订单同时刻（固定时钟）只允许一次执行中调整
        ApiException sameTime = assertThrows(ApiException.class, () ->
                service.adjustBlendRatio(orderNo, new AdjustBlendRatioRequest("adj-2", new BigDecimal("21.000"))));
        assertEquals(409, sameTime.getStatus());
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM blend_ratio_version WHERE order_no = ?", Long.class, orderNo),
                "同时刻第二次调整不得产生新版本");

        // 调整越界 400
        ApiException outOfInterval = assertThrows(ApiException.class, () ->
                service.adjustBlendRatio(orderNo, new AdjustBlendRatioRequest("adj-3", new BigDecimal("30.000"))));
        assertEquals(400, outOfInterval.getStatus());

        // 新目标 20.000 下的读数按 v2 复算偏离
        service.submitBlendReading(reading("rd-e2", orderNo, T0.plusMinutes(1), "22.000"));
        BlendRecomputeView recompute = service.recomputeBlend(orderNo);
        assertEquals(2, recompute.versions().size());
        assertEquals(2, recompute.readings().size());
        assertEquals(1, recompute.readings().get(0).versionNo());
        assertEquals(0, new BigDecimal("3.000").compareTo(recompute.readings().get(0).deviationPct()));
        assertEquals(2, recompute.readings().get(1).versionNo());
        assertEquals(0, new BigDecimal("2.000").compareTo(recompute.readings().get(1).deviationPct()),
                "按 v2 目标复算偏离恰 2%");
        assertFalse(recompute.readings().get(1).outOfTolerance(), "恰 2% 合格");
    }

    @Test
    @DisplayName("过渡卷判定不受比例影响沿用原口径；归档后版本链与掺配轨迹仍可复算")
    void rollVerdictUnaffectedAndArchivedRecompute() {
        OrderView order = applyWithRatioAndExecute("blend-f1", "PM-B10", "25.000");
        String orderNo = order.orderNo();
        service.submitBlendReading(reading("rd-f1", orderNo, T0, "28.000"));

        service.dispose(orderNo, new DisposeRollsRequest(List.of(
                new DisposeRollsRequest.RollItem("R-BL-9", new BigDecimal("500.000")))));
        // 过渡卷强制降级与原正品门槛口径不变
        RollView roll = service.submitInspection(new InspectionRequest(
                "insp-f1", "R-BL-9", new BigDecimal("18.000"), new BigDecimal("4.000"),
                new BigDecimal("45.000"), new BigDecimal("77.00"))).data();
        assertEquals("DOWNGRADED", roll.verdict());
        assertEquals("NON_GENUINE", roll.warehouseStatus());
        assertTrue(roll.eligibleReexam(), "恰达门槛仅取得复议资格，口径不变");

        service.complete(orderNo);
        service.archive(orderNo);

        // 归档后历史订单可按版本复算实际掺配轨迹
        BlendRecomputeView recompute = service.recomputeBlend(orderNo);
        assertEquals(TransitionStatus.ARCHIVED, recompute.status());
        assertEquals(1, recompute.versions().size());
        assertEquals(0, new BigDecimal("25.000").compareTo(recompute.versions().get(0).targetRatioPct()));
        assertEquals(1, recompute.readings().size());
        assertEquals(0, new BigDecimal("3.000").compareTo(recompute.readings().get(0).deviationPct()));
        assertTrue(recompute.readings().get(0).outOfTolerance());
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    /** 申请携带比例（同事务判定）并执行，返回 EXECUTED 状态单据。 */
    private OrderView applyWithRatioAndExecute(String requestId, String machine, String ratio) {
        OrderView applied = service.apply(new ApplyOrderRequest(
                requestId, machine, "G-A4-70", "G-A4-77", new BigDecimal(ratio))).data();
        assertEquals(TransitionStatus.DECIDED, applied.status());
        return service.execute(applied.orderNo(), new ExecuteOrderRequest(
                new BigDecimal("900.000"), new BigDecimal("1.000"), 60));
    }

    private BlendReadingRequest reading(String requestId, String orderNo,
                                        LocalDateTime sampledAt, String actualRatio) {
        return new BlendReadingRequest(requestId, orderNo, sampledAt, new BigDecimal(actualRatio));
    }
}
