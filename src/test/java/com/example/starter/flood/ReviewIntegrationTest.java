package com.example.starter.flood;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.flood.exception.FloodControlException;
import com.example.starter.flood.service.ReviewService;
import com.example.starter.flood.web.dto.ReviewRequest;
import com.example.starter.flood.web.dto.ReviewView;
import com.example.starter.flood.web.dto.WarningView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 复盘归档 H2 集成测试：归档终态、重复归档幂等成功、执行中方案禁止归档。
 */
class ReviewIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ReviewService reviewService;
    @Autowired
    private FlowSupport flow;

    @Test
    @DisplayName("归档为终态：重复归档返回首次结果（幂等成功，replay=true）")
    void archiveIsIdempotentTerminal() {
        WarningView warning = flow.openWarning("w-review-idem");
        ReviewView first = reviewService.archive(new ReviewRequest("rv-idem",
                warning.warningNo(), "首次复盘：雨情平稳"));
        assertThat(first.status()).isEqualTo("ARCHIVED");
        assertThat(first.replay()).isFalse();
        assertThat(first.note()).isEqualTo("首次复盘：雨情平稳");

        ReviewView again = reviewService.archive(new ReviewRequest("rv-idem-other",
                warning.warningNo(), "尝试再次复盘（参数不同也应幂等返回首次）"));
        assertThat(again.replay()).isTrue();
        assertThat(again.status()).isEqualTo("ARCHIVED");
        assertThat(again.archivedAt()).isEqualTo(first.archivedAt());
        assertThat(again.note()).isEqualTo("首次复盘：雨情平稳");

        Integer chainCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM warning_chain WHERE warning_no = ? AND status = 'ARCHIVED'",
                Integer.class, warning.warningNo());
        assertThat(chainCount).isEqualTo(1);
        Integer reviewCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM review_record r JOIN warning_chain w ON r.warning_id = w.id "
                        + "WHERE w.warning_no = ?", Integer.class, warning.warningNo());
        assertThat(reviewCount).isEqualTo(1);
    }

    @Test
    @DisplayName("方案仍在执行中（未回执闭环）时禁止归档")
    void archiveRejectedWhilePlanEffective() {
        WarningView warning = flow.openWarning("w-review-blocked");
        var routing = flow.route("r-review-blocked", warning.warningNo(),
                new java.math.BigDecimal("200.0000"), new java.math.BigDecimal("400001"),
                new java.math.BigDecimal("150"), new java.math.BigDecimal("10"));
        var plan = flow.createPlan("p-review-blocked", warning.warningNo(),
                routing.routingNo(), FlowSupport.safeBoundaryOpenings());
        assertThatThrownBy(() -> reviewService.archive(new ReviewRequest("rv-blocked",
                warning.warningNo(), "未闭环就归档")))
                .isInstanceOf(FloodControlException.class)
                .hasMessageContaining("仍在执行中");

        // 闭环后允许归档
        flow.sign(plan.orderNo(), "值班员甲");
        flow.execute(plan.orderNo(), "值班员甲");
        flow.receipt(plan.orderNo(), "rc-review-blocked", "值班员甲",
                FlowSupport.safeBoundaryOpenings(), null);
        ReviewView view = reviewService.archive(new ReviewRequest("rv-blocked-2",
                warning.warningNo(), "闭环后复盘"));
        assertThat(view.status()).isEqualTo("ARCHIVED");
        String planStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM release_plan WHERE plan_no = ?", String.class, plan.planNo());
        assertThat(planStatus).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("预警幂等：同键同参重放返回首次处置链，同键异参 409")
    void warningIdempotency() {
        WarningView first = flow.openWarning("w-warning-idem");
        WarningView replay = flow.openWarning("w-warning-idem");
        assertThat(replay.warningNo()).isEqualTo(first.warningNo());

        assertThatThrownBy(() -> flow.warningWithDifferentTitle("w-warning-idem"))
                .isInstanceOf(FloodControlException.class)
                .hasMessageContaining("不同参数的预警");
    }
}
