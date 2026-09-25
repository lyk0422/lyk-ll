package com.example.starter.flood;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.flood.exception.FloodControlException;
import com.example.starter.flood.repo.PlanPO;
import com.example.starter.flood.repo.PlanRepository;
import com.example.starter.flood.service.PlanService;
import com.example.starter.flood.web.dto.OpeningItem;
import com.example.starter.flood.web.dto.PlanView;
import com.example.starter.flood.web.dto.RoutingView;
import com.example.starter.flood.web.dto.WarningView;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 泄流方案 H2 集成测试：泄量恰等安全值合法、超限拒绝、同库执行中唯一、幂等重放。
 */
class PlanIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PlanService planService;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private FlowSupport flow;

    private RoutingView releaseRequiredRouting(String warningReq, String routingReq,
                                               String warningNo) {
        return flow.route(routingReq, warningNo, new BigDecimal("200.0000"),
                new BigDecimal("400001"), new BigDecimal("150"), new BigDecimal("10"));
    }

    @Test
    @DisplayName("总泄量恰等于下游安全泄量 20 m3/s：合法生效并下达指令")
    void totalReleaseExactlySafeLimitAllowed() {
        WarningView warning = flow.openWarning("w-plan-safe");
        RoutingView routing = releaseRequiredRouting("w-plan-safe", "r-plan-safe",
                warning.warningNo());
        PlanView plan = flow.createPlan("p-plan-safe", warning.warningNo(),
                routing.routingNo(), FlowSupport.safeBoundaryOpenings());
        assertThat(plan.totalRelease()).isEqualByComparingTo("20.000");
        assertThat(plan.downstreamSafeRelease()).isEqualByComparingTo("20.000");
        assertThat(plan.status()).isEqualTo("EFFECTIVE");
        assertThat(plan.orderNo()).isNotNull();
        assertThat(plan.orderStatus()).isEqualTo("ISSUED");
        assertThat(plan.replay()).isFalse();
    }

    @Test
    @DisplayName("总泄量超过下游安全泄量：拒绝，不产生方案")
    void totalReleaseExceedsSafeLimitRejected() {
        WarningView warning = flow.openWarning("w-plan-over");
        RoutingView routing = flow.route("r-plan-over", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("400001"),
                new BigDecimal("150"), new BigDecimal("10"));
        List<OpeningItem> over = List.of(
                new OpeningItem("1#闸门", new BigDecimal("1.0"), new BigDecimal("15.000")),
                new OpeningItem("2#闸门", new BigDecimal("1.0"), new BigDecimal("5.001")));
        assertThatThrownBy(() -> flow.createPlan("p-plan-over", warning.warningNo(),
                routing.routingNo(), over))
                .isInstanceOf(FloodControlException.class)
                .hasMessageContaining("超过下游安全泄量");
        Integer planCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_plan", Integer.class);
        assertThat(planCount).isZero();
    }

    @Test
    @DisplayName("演算结论非须泄流/险时禁止制定方案")
    void planRejectedWhenNotRequired() {
        WarningView warning = flow.openWarning("w-plan-not-needed");
        RoutingView routing = flow.route("r-plan-not-needed", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("300000"),
                new BigDecimal("150"), new BigDecimal("10"));
        assertThat(routing.dangerJudgment()).isEqualTo("SAFE");
        assertThatThrownBy(() -> flow.createPlan("p-plan-not-needed", warning.warningNo(),
                routing.routingNo(), FlowSupport.safeBoundaryOpenings()))
                .isInstanceOf(FloodControlException.class)
                .hasMessageContaining("无需制定泄流方案");
    }

    @Test
    @DisplayName("同一尾矿库同时刻只允许一个执行中方案；回执闭环后可再制定")
    void onlyOneEffectivePlanPerPond() {
        WarningView warning = flow.openWarning("w-plan-unique");
        RoutingView routing = flow.route("r-plan-unique", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("400001"),
                new BigDecimal("150"), new BigDecimal("10"));
        PlanView first = flow.createPlan("p-plan-unique-1", warning.warningNo(),
                routing.routingNo(), FlowSupport.safeBoundaryOpenings());

        // 同一处置链下第二个执行中方案必须被唯一约束拒绝
        assertThatThrownBy(() -> flow.createPlan("p-plan-unique-2", warning.warningNo(),
                routing.routingNo(), List.of(new OpeningItem("备用孔口",
                        new BigDecimal("0.5"), new BigDecimal("5.000")))))
                .isInstanceOf(FloodControlException.class)
                .hasMessageContaining("执行中的泄流方案");

        // 回执闭环解除执行中位
        flow.sign(first.orderNo(), "值班员甲");
        flow.execute(first.orderNo(), "值班员甲");
        flow.receipt(first.orderNo(), "rc-plan-unique", "值班员甲",
                FlowSupport.safeBoundaryOpenings(), null);
        PlanPO completed = planRepository.findByNo(first.planNo());
        assertThat(completed.status).isEqualTo("COMPLETED");
        assertThat(completed.activeKey).isNull();

        // 闭环后同库可再制定方案
        PlanView second = flow.createPlan("p-plan-unique-3", warning.warningNo(),
                routing.routingNo(), List.of(new OpeningItem("备用孔口",
                        new BigDecimal("0.5"), new BigDecimal("5.000"))));
        assertThat(second.status()).isEqualTo("EFFECTIVE");
    }

    @Test
    @DisplayName("方案幂等：同键同参重放返回首次方案，异参 409")
    void planIdempotency() {
        WarningView warning = flow.openWarning("w-plan-idem");
        RoutingView routing = flow.route("r-plan-idem", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("400001"),
                new BigDecimal("150"), new BigDecimal("10"));
        PlanView first = flow.createPlan("p-plan-idem", warning.warningNo(),
                routing.routingNo(), FlowSupport.safeBoundaryOpenings());
        PlanView replay = flow.createPlan("p-plan-idem", warning.warningNo(),
                routing.routingNo(), List.of(new OpeningItem("1#排洪隧洞工作闸门",
                        new BigDecimal("1.200"), new BigDecimal("20.000"))));
        assertThat(replay.replay()).isTrue();
        assertThat(replay.planNo()).isEqualTo(first.planNo());
        assertThat(replay.orderNo()).isEqualTo(first.orderNo());

        assertThatThrownBy(() -> flow.createPlan("p-plan-idem", warning.warningNo(),
                routing.routingNo(), List.of(new OpeningItem("1#排洪隧洞工作闸门",
                        new BigDecimal("1.000"), new BigDecimal("18.000")))))
                .isInstanceOf(FloodControlException.class)
                .hasMessageContaining("不同参数的泄流方案");
    }

    @Test
    @DisplayName("超安全泄量的失败请求不残留幂等键：同键改为合法泄量后可成功重试")
    void failedValidationDoesNotConsumeIdempotencyKey() {
        WarningView warning = flow.openWarning("w-plan-retry");
        RoutingView routing = flow.route("r-plan-retry", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("400001"),
                new BigDecimal("150"), new BigDecimal("10"));
        // 首次同键请求超安全泄量：拒绝且不落业务数据
        assertThatThrownBy(() -> flow.createPlan("p-plan-retry", warning.warningNo(),
                routing.routingNo(), List.of(new OpeningItem("1#闸门",
                        new BigDecimal("1.0"), new BigDecimal("20.001")))))
                .isInstanceOf(FloodControlException.class)
                .hasMessageContaining("超过下游安全泄量");
        // 同键修正为恰等安全泄量：允许成功（证明失败未占用 requestId）
        PlanView plan = flow.createPlan("p-plan-retry", warning.warningNo(),
                routing.routingNo(), FlowSupport.safeBoundaryOpenings());
        assertThat(plan.status()).isEqualTo("EFFECTIVE");
        assertThat(plan.totalRelease()).isEqualByComparingTo("20.000");
    }
}