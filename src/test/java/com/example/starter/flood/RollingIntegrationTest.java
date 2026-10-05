package com.example.starter.flood;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.flood.exception.FloodControlException;
import com.example.starter.flood.service.PlanService;
import com.example.starter.flood.service.ReviewService;
import com.example.starter.flood.service.RollingService;
import com.example.starter.flood.service.SuggestionService;
import com.example.starter.flood.web.dto.ConfirmSuggestionRequest;
import com.example.starter.flood.web.dto.LevelTraceView;
import com.example.starter.flood.web.dto.PlanView;
import com.example.starter.flood.web.dto.RecomputeView;
import com.example.starter.flood.web.dto.ReviewRequest;
import com.example.starter.flood.web.dto.RollingCompareView;
import com.example.starter.flood.web.dto.RollingVersionView;
import com.example.starter.flood.web.dto.RoutingView;
import com.example.starter.flood.web.dto.SuggestionView;
import com.example.starter.flood.web.dto.WarningView;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 滚动调洪演算 H2 集成测试：恰满 30 分钟触发新版本、旧版本保留可查、
 * 泄流能力不足同事务提级并追加方案版本、在途指令不溯及、替代建议人工确认留痕、
 * 版本对比与单版本复算、复盘归档终态语义不变。
 */
class RollingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RollingService rollingService;
    @Autowired
    private SuggestionService suggestionService;
    @Autowired
    private PlanService planService;
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private FlowSupport flow;

    /** 手工制定 20 m3/s 方案（v1）并返回方案视图。 */
    private PlanView manualPlan(String suffix, String warningNo) {
        RoutingView routing = flow.route("r-" + suffix, warningNo,
                new BigDecimal("200.0000"), new BigDecimal("400001"),
                new BigDecimal("150"), new BigDecimal("10"));
        return flow.createPlan("p-" + suffix, warningNo, routing.routingNo(),
                FlowSupport.safeBoundaryOpenings());
    }

    @Test
    @DisplayName("恰满 30 分钟触发新版本：29 分钟拒绝，恰 30 分钟生成 v2，v1 保留可查")
    void rollingTriggeredExactlyEvery30Minutes() {
        WarningView warning = flow.openWarning("w-roll-30min");
        flow.submitHydrograph("hg-roll-30min", warning.warningNo(),
                new BigDecimal("5"), new BigDecimal("8"), new BigDecimal("6"));
        PlanView plan = manualPlan("roll-30min", warning.warningNo());
        assertThat(plan.versionNo()).isEqualTo(1);

        RollingVersionView v1 = flow.roll("gd-30min-1", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("150"), new BigDecimal("10"));
        assertThat(v1.versionNo()).isEqualTo(1);
        assertThat(v1.releaseJudgment()).isEqualTo("SUFFICIENT");
        assertThat(v1.inflowRate()).isEqualByComparingTo("5.000");
        assertThat(v1.requiredRelease()).isEqualByComparingTo("8.000");
        assertThat(v1.releaseCapacity()).isEqualByComparingTo("20.000");
        assertThat(v1.periodInflowVolume()).isEqualByComparingTo("9000.0000");
        assertThat(v1.projectedStorage()).isEqualByComparingTo("573000.0000");
        assertThat(v1.warningLevelAfter()).isEqualTo("ORANGE");
        assertThat(v1.planVersionAfter()).isEqualTo(1);
        assertThat(v1.replay()).isFalse();

        // 未满 30 分钟：拒绝触发
        clock.advanceMinutes(29);
        assertThatThrownBy(() -> flow.roll("gd-30min-early", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("150"), new BigDecimal("10")))
                .isInstanceOf(FloodControlException.class)
                .hasMessageContaining("恰满后触发");

        // 恰满 30 分钟：触发 v2
        clock.advanceMinutes(1);
        RollingVersionView v2 = flow.roll("gd-30min-2", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("150"), new BigDecimal("10"));
        assertThat(v2.versionNo()).isEqualTo(2);
        assertThat(v2.inflowRate()).isEqualByComparingTo("8.000");
        assertThat(v2.scheduledTime()).isEqualTo(
                v1.scheduledTime().plusMinutes(30));

        // 旧版本保留可查
        List<RollingVersionView> versions = rollingService.listVersions(warning.warningNo());
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).versionNo()).isEqualTo(1);
        assertThat(versions.get(1).versionNo()).isEqualTo(2);
        Integer stored = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM rolling_routing", Integer.class);
        assertThat(stored).isEqualTo(2);
    }

    @Test
    @DisplayName("泄流能力不足：同事务提升预警级别并追加方案版本，在途指令不溯及，建议留痕")
    void insufficientEscalatesAndAppendsPlanVersionAtomically() {
        WarningView warning = flow.openWarning("w-roll-insufficient");
        flow.submitHydrograph("hg-insufficient", warning.warningNo(),
                new BigDecimal("10"), new BigDecimal("25"), new BigDecimal("12"));
        PlanView planV1 = manualPlan("insufficient", warning.warningNo());
        String orderNo = planV1.orderNo();

        RollingVersionView v1 = flow.roll("gd-insufficient-1", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("150"), new BigDecimal("10"));
        assertThat(v1.releaseJudgment()).isEqualTo("INSUFFICIENT");
        assertThat(v1.requiredRelease()).isEqualByComparingTo("25.000");
        assertThat(v1.releaseCapacity()).isEqualByComparingTo("20.000");
        assertThat(v1.warningLevelAfter()).isEqualTo("RED");
        assertThat(v1.planVersionAfter()).isEqualTo(2);
        assertThat(v1.dangerJudgment()).isEqualTo("RELEASE_REQUIRED");

        // 级别提升留痕：ORANGE -> RED
        LevelTraceView trace = rollingService.levelTrace(warning.warningNo());
        assertThat(trace.currentLevel()).isEqualTo("RED");
        assertThat(trace.events()).hasSize(1);
        assertThat(trace.events().get(0).fromLevel()).isEqualTo("ORANGE");
        assertThat(trace.events().get(0).toLevel()).isEqualTo("RED");
        assertThat(trace.events().get(0).rollingNo()).isEqualTo(v1.rollingNo());

        // 方案版本追加：v1 SUPERSEDED 留痕，v2 EFFECTIVE（泄量按下游安全泄量 20 封顶）
        List<PlanView> plans = planService.listVersions(warning.warningNo());
        assertThat(plans).hasSize(2);
        assertThat(plans.get(0).versionNo()).isEqualTo(1);
        assertThat(plans.get(0).status()).isEqualTo("SUPERSEDED");
        assertThat(plans.get(0).supersededAt()).isNotNull();
        assertThat(plans.get(1).versionNo()).isEqualTo(2);
        assertThat(plans.get(1).status()).isEqualTo("EFFECTIVE");
        assertThat(plans.get(1).totalRelease()).isEqualByComparingTo("20.000");

        // 在途指令只认创建时的方案版本 v1，新版本不溯及
        var order = jdbcTemplate.queryForMap(
                "SELECT plan_version_no, status FROM release_order WHERE order_no = ?", orderNo);
        assertThat(((Number) order.get("plan_version_no")).intValue()).isEqualTo(1);
        assertThat(order.get("status")).isEqualTo("ISSUED");

        // 冲突替代建议已生成（PENDING 留痕可查）
        List<SuggestionView> suggestions = suggestionService.listByOrder(orderNo);
        assertThat(suggestions).hasSize(1);
        SuggestionView suggestion = suggestions.get(0);
        assertThat(suggestion.status()).isEqualTo("PENDING");
        assertThat(suggestion.oldVersionNo()).isEqualTo(1);
        assertThat(suggestion.newVersionNo()).isEqualTo(2);
        assertThat(suggestion.suggestionDetail()).contains("不溯及在途指令");

        // 在途指令仍按 v1 执行闭环（新版本不溯及）
        flow.sign(orderNo, "值班员甲");
        flow.execute(orderNo, "值班员甲");
        flow.receipt(orderNo, "rc-insufficient", "值班员甲",
                FlowSupport.safeBoundaryOpenings(), null);

        // 人工确认替代建议：留痕可查
        SuggestionView confirmed = suggestionService.confirm(suggestion.suggestionNo(),
                new ConfirmSuggestionRequest("cf-1", "调度长", "ADOPT_NEW_VERSION",
                        "同意按 v2 另行下达"));
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.decision()).isEqualTo("ADOPT_NEW_VERSION");
        assertThat(confirmed.confirmedBy()).isEqualTo("调度长");
        assertThat(confirmed.confirmedAt()).isNotNull();

        // 确认幂等：同键同参重放首次结果，同键异参 409，他键重复确认拒绝
        SuggestionView replay = suggestionService.confirm(suggestion.suggestionNo(),
                new ConfirmSuggestionRequest("cf-1", "调度长", "ADOPT_NEW_VERSION",
                        "同意按 v2 另行下达"));
        assertThat(replay.replay()).isTrue();
        assertThatThrownBy(() -> suggestionService.confirm(suggestion.suggestionNo(),
                new ConfirmSuggestionRequest("cf-1", "调度长", "KEEP_ORIGINAL_VERSION", null)))
                .isInstanceOf(FloodControlException.class)
                .hasMessageContaining("不同参数的建议确认");
        assertThatThrownBy(() -> suggestionService.confirm(suggestion.suggestionNo(),
                new ConfirmSuggestionRequest("cf-2", "调度长", "KEEP_ORIGINAL_VERSION", null)))
                .isInstanceOf(FloodControlException.class)
                .hasMessageContaining("已人工确认");

        // 确认留痕可查
        List<SuggestionView> traced = suggestionService.listByOrder(orderNo);
        assertThat(traced).hasSize(1);
        assertThat(traced.get(0).status()).isEqualTo("CONFIRMED");
        assertThat(traced.get(0).decision()).isEqualTo("ADOPT_NEW_VERSION");
    }

    @Test
    @DisplayName("泄流能力恰等需求判临界：不提级、不追加方案版本")
    void exactlySufficientIsCriticalNoEscalation() {
        WarningView warning = flow.openWarning("w-roll-critical");
        flow.submitHydrograph("hg-critical", warning.warningNo(),
                new BigDecimal("20"), new BigDecimal("20"));
        manualPlan("critical", warning.warningNo());

        RollingVersionView v1 = flow.roll("gd-critical-1", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("150"), new BigDecimal("10"));
        assertThat(v1.releaseJudgment()).isEqualTo("CRITICAL");
        assertThat(v1.warningLevelAfter()).isEqualTo("ORANGE");
        assertThat(v1.planVersionAfter()).isEqualTo(1);

        LevelTraceView trace = rollingService.levelTrace(warning.warningNo());
        assertThat(trace.currentLevel()).isEqualTo("ORANGE");
        assertThat(trace.events()).isEmpty();
        assertThat(planService.listVersions(warning.warningNo())).hasSize(1);
    }

    @Test
    @DisplayName("滚动演算幂等：同键同参重放首次版本，同键异参 409；过程线录入同口径")
    void rollingIdempotency() {
        WarningView warning = flow.openWarning("w-roll-idem");
        flow.submitHydrograph("hg-idem", warning.warningNo(),
                new BigDecimal("5"), new BigDecimal("8"));
        manualPlan("idem", warning.warningNo());

        RollingVersionView first = flow.roll("gd-idem-1", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("150"), new BigDecimal("10"));
        RollingVersionView replay = flow.roll("gd-idem-1", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("150"), new BigDecimal("10"));
        assertThat(replay.replay()).isTrue();
        assertThat(replay.rollingNo()).isEqualTo(first.rollingNo());
        assertThat(rollingService.listVersions(warning.warningNo())).hasSize(1);

        assertThatThrownBy(() -> flow.roll("gd-idem-1", warning.warningNo(),
                new BigDecimal("201.0000"), new BigDecimal("150"), new BigDecimal("10")))
                .isInstanceOfSatisfying(FloodControlException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("IDEMPOTENT_CONFLICT");
                });

        // 过程线录入幂等
        var hydroReplay = flow.submitHydrograph("hg-idem", warning.warningNo(),
                new BigDecimal("5"), new BigDecimal("8"));
        assertThat(hydroReplay.replay()).isTrue();
        assertThatThrownBy(() -> flow.submitHydrograph("hg-idem", warning.warningNo(),
                new BigDecimal("5"), new BigDecimal("9")))
                .isInstanceOf(FloodControlException.class)
                .hasMessageContaining("不同参数的过程线录入");
    }

    @Test
    @DisplayName("版本对比查询与任一版本单独复算（源输入快照）")
    void compareAndRecomputeFromSnapshot() {
        WarningView warning = flow.openWarning("w-roll-compare");
        flow.submitHydrograph("hg-compare", warning.warningNo(),
                new BigDecimal("5"), new BigDecimal("8"), new BigDecimal("6"));
        manualPlan("compare", warning.warningNo());

        RollingVersionView v1 = flow.roll("gd-compare-1", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("150"), new BigDecimal("10"));
        clock.advanceMinutes(30);
        RollingVersionView v2 = flow.roll("gd-compare-2", warning.warningNo(),
                new BigDecimal("201.0000"), new BigDecimal("150"), new BigDecimal("10"));

        RollingCompareView compare = rollingService.compare(warning.warningNo(), 1, 2);
        assertThat(compare.fromVersion()).isEqualTo(1);
        assertThat(compare.toVersion()).isEqualTo(2);
        assertThat(compare.from().rollingNo()).isEqualTo(v1.rollingNo());
        assertThat(compare.to().rollingNo()).isEqualTo(v2.rollingNo());
        assertThat(compare.inflowRateDelta()).isEqualByComparingTo("3.000");
        assertThat(compare.storageDelta()).isEqualByComparingTo("40000.0000");
        assertThat(compare.releaseJudgmentChange()).isEqualTo("SUFFICIENT->SUFFICIENT");
        assertThat(compare.warningLevelChange()).isEqualTo("ORANGE->ORANGE");

        // 任一版本单独复算：仅按留存快照重算，与存库一致
        RecomputeView recomputeV1 = rollingService.recompute(v1.rollingNo());
        assertThat(recomputeV1.consistent()).isTrue();
        assertThat(recomputeV1.recomputedStorage()).isEqualByComparingTo("600000.0000");
        RecomputeView recomputeV2 = rollingService.recompute(v2.rollingNo());
        assertThat(recomputeV2.consistent()).isTrue();
        assertThat(recomputeV2.recomputedStorage()).isEqualByComparingTo("640000.0000");

        assertThatThrownBy(() -> rollingService.compare(warning.warningNo(), 1, 9))
                .isInstanceOf(FloodControlException.class)
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> rollingService.recompute("GD-NOT-EXIST"))
                .isInstanceOf(FloodControlException.class);
    }

    @Test
    @DisplayName("追加方案与级别提升同事务：他链占用执行中方案时整体回滚，不留级别与版本痕迹")
    void appendAndEscalationRollbackAtomically() {
        // 处置链 A 占用同库执行中方案
        WarningView warningA = flow.openWarning("w-roll-atomic-a");
        RoutingView routingA = flow.route("r-atomic-a", warningA.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("400001"),
                new BigDecimal("150"), new BigDecimal("10"));
        flow.createPlan("p-atomic-a", warningA.warningNo(), routingA.routingNo(),
                FlowSupport.safeBoundaryOpenings());

        // 处置链 B（同库）无方案，过程线峰值 25 > 0 → 不足，追加方案时撞执行中唯一约束
        WarningView warningB = flow.openWarning("w-roll-atomic-b");
        flow.submitHydrograph("hg-atomic-b", warningB.warningNo(),
                new BigDecimal("10"), new BigDecimal("25"));
        assertThatThrownBy(() -> flow.roll("gd-atomic-b1", warningB.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("150"), new BigDecimal("10")))
                .isInstanceOf(FloodControlException.class)
                .hasMessageContaining("已存在执行中的泄流方案");

        // 整体回滚：B 级别未变、无级别事件、无滚动版本、无新方案、幂等键未残留
        LevelTraceView trace = rollingService.levelTrace(warningB.warningNo());
        assertThat(trace.currentLevel()).isEqualTo("ORANGE");
        assertThat(trace.events()).isEmpty();
        assertThat(rollingService.listVersions(warningB.warningNo())).isEmpty();
        Integer planCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_plan", Integer.class);
        assertThat(planCount).isEqualTo(1);
        Integer idemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = 'gd-atomic-b1'",
                Integer.class);
        assertThat(idemCount).isZero();

        // 同键可修正重试（过程线改全 0，能力恰等需求判临界不触发追加）：证明失败未占用幂等键
        flow.submitHydrograph("hg-atomic-b2", warningB.warningNo(),
                new BigDecimal("0"), new BigDecimal("0"));
        RollingVersionView retry = flow.roll("gd-atomic-b1", warningB.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("150"), new BigDecimal("10"));
        assertThat(retry.versionNo()).isEqualTo(1);
        assertThat(retry.releaseJudgment()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("复盘归档终态语义不变：多版本方案随归档全部 ARCHIVED，重复归档幂等")
    void archiveTerminalSemanticsWithVersions() {
        WarningView warning = flow.openWarning("w-roll-archive");
        flow.submitHydrograph("hg-archive", warning.warningNo(),
                new BigDecimal("10"), new BigDecimal("25"));
        PlanView planV1 = manualPlan("archive", warning.warningNo());
        // 触发不足：提级 + 追加 v2；v1 指令回执闭环
        flow.roll("gd-archive-1", warning.warningNo(), new BigDecimal("200.0000"),
                new BigDecimal("150"), new BigDecimal("10"));
        flow.sign(planV1.orderNo(), "值班员甲");
        flow.execute(planV1.orderNo(), "值班员甲");
        flow.receipt(planV1.orderNo(), "rc-archive", "值班员甲",
                FlowSupport.safeBoundaryOpenings(), null);

        var archived = reviewService.archive(new ReviewRequest("rv-roll-archive",
                warning.warningNo(), "滚动演算复盘"));
        assertThat(archived.status()).isEqualTo("ARCHIVED");
        assertThat(archived.replay()).isFalse();

        // 全部方案版本随归档进入终态
        List<String> statuses = jdbcTemplate.queryForList(
                "SELECT status FROM release_plan ORDER BY version_no", String.class);
        assertThat(statuses).containsExactly("ARCHIVED", "ARCHIVED");

        // 重复归档幂等返回首次结果
        var again = reviewService.archive(new ReviewRequest("rv-roll-archive-2",
                warning.warningNo(), "再次归档"));
        assertThat(again.replay()).isTrue();
        assertThat(again.archivedAt()).isEqualTo(archived.archivedAt());

        // 归档后滚动演算拒绝（终态保护）
        clock.advanceMinutes(30);
        assertThatThrownBy(() -> flow.roll("gd-archive-2", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("150"), new BigDecimal("10")))
                .isInstanceOf(FloodControlException.class)
                .hasMessageContaining("已归档");
    }

    @Test
    @DisplayName("无过程线拒绝滚动演算；失败请求不残留幂等键")
    void rollingRequiresHydrograph() {
        WarningView warning = flow.openWarning("w-roll-nohydro");
        assertThatThrownBy(() -> flow.roll("gd-nohydro-1", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("150"), new BigDecimal("10")))
                .isInstanceOf(FloodControlException.class)
                .hasMessageContaining("尚未录入入库洪水过程线");
        Integer idemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = 'gd-nohydro-1'",
                Integer.class);
        assertThat(idemCount).isZero();
    }
}
