package com.example.starter.flood;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.flood.exception.FloodControlException;
import com.example.starter.flood.service.AdviceService;
import com.example.starter.flood.service.ReviewService;
import com.example.starter.flood.service.RollingRoutingService;
import com.example.starter.flood.web.dto.AdviceConfirmRequest;
import com.example.starter.flood.web.dto.AdviceView;
import com.example.starter.flood.web.dto.InflowPoint;
import com.example.starter.flood.web.dto.PlanView;
import com.example.starter.flood.web.dto.ReviewRequest;
import com.example.starter.flood.web.dto.RollingRoutingRequest;
import com.example.starter.flood.web.dto.RollingRoutingView;
import com.example.starter.flood.web.dto.RoutingView;
import com.example.starter.flood.web.dto.WarningView;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 替代建议人工确认 H2 集成测试：确认留痕、按新版本下达或维持旧指令、
 * 确认幂等与 409、旧指令回执后处置链可归档（终态语义不变）。
 */
class AdviceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RollingRoutingService rollingService;
    @Autowired
    private AdviceService adviceService;
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private FlowSupport flow;

    private static List<InflowPoint> hydrograph(String rate) {
        return List.of(new InflowPoint(0, new BigDecimal(rate)),
                new InflowPoint(30, new BigDecimal(rate)));
    }

    /** 构造“在途指令 + 新方案版本 + PENDING 建议”场景。 */
    private RollingRoutingView prepareConflict(String tag) {
        WarningView warning = flow.openWarning("w-advice-" + tag);
        RoutingView routing = flow.route("r-advice-" + tag, warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("400001"),
                new BigDecimal("150"), new BigDecimal("10"));
        flow.createPlan("p-advice-" + tag, warning.warningNo(),
                routing.routingNo(), FlowSupport.safeBoundaryOpenings());
        clock.advanceMinutes(30);
        return rollingService.roll(new RollingRoutingRequest("rr-advice-" + tag,
                warning.warningNo(), hydrograph("300"), null, null));
    }

    @Test
    @DisplayName("人工确认 ACCEPT_NEW_VERSION：按新版本下达新指令（钉住版本2），在途旧指令仍钉版本1，确认留痕可查")
    void confirmAcceptNewVersion() {
        RollingRoutingView v2 = prepareConflict("accept");
        String adviceNo = v2.conflictAdviceNo();

        AdviceView confirmed = adviceService.confirm(adviceNo,
                new AdviceConfirmRequest("c-accept-1", "调度员甲", "ACCEPT_NEW_VERSION",
                        "同意按新版本泄流"));
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.decision()).isEqualTo("ACCEPT_NEW_VERSION");
        assertThat(confirmed.confirmedBy()).isEqualTo("调度员甲");
        assertThat(confirmed.confirmedAt()).isNotNull();
        assertThat(confirmed.replay()).isFalse();
        assertThat(confirmed.newPlanVersion()).isEqualTo(2);
        assertThat(confirmed.orderPlanVersion()).isEqualTo(1);

        // 新指令钉住方案版本 2；在途旧指令仍钉版本 1（新版本不溯及在途指令）
        Integer newOrderVersion = jdbcTemplate.queryForObject(
                "SELECT plan_version FROM release_order o JOIN release_plan p ON o.plan_id = p.id "
                        + "WHERE p.version = 2", Integer.class);
        assertThat(newOrderVersion).isEqualTo(2);
        Integer oldOrderVersion = jdbcTemplate.queryForObject(
                "SELECT plan_version FROM release_order o JOIN release_plan p ON o.plan_id = p.id "
                        + "WHERE p.version = 1", Integer.class);
        assertThat(oldOrderVersion).isEqualTo(1);
        Integer orderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_order", Integer.class);
        assertThat(orderCount).isEqualTo(2);

        // 留痕可查
        AdviceView trace = adviceService.get(adviceNo);
        assertThat(trace.status()).isEqualTo("CONFIRMED");
        assertThat(trace.confirmNote()).isEqualTo("同意按新版本泄流");
        assertThat(trace.suggestion()).contains("recommendation").contains("版本");
    }

    @Test
    @DisplayName("确认幂等：同键同参重放首次确认结果，同键异参 409，异键重复确认 422")
    void confirmIdempotency() {
        RollingRoutingView v2 = prepareConflict("idem");
        String adviceNo = v2.conflictAdviceNo();

        AdviceView first = adviceService.confirm(adviceNo,
                new AdviceConfirmRequest("c-idem-1", "调度员甲", "KEEP_CURRENT", null));
        assertThat(first.replay()).isFalse();

        AdviceView replay = adviceService.confirm(adviceNo,
                new AdviceConfirmRequest("c-idem-1", "调度员甲", "KEEP_CURRENT", null));
        assertThat(replay.replay()).isTrue();
        assertThat(replay.decision()).isEqualTo("KEEP_CURRENT");

        assertThatThrownBy(() -> adviceService.confirm(adviceNo,
                new AdviceConfirmRequest("c-idem-1", "调度员甲", "ACCEPT_NEW_VERSION", null)))
                .isInstanceOfSatisfying(FloodControlException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("IDEMPOTENT_CONFLICT");
                });

        assertThatThrownBy(() -> adviceService.confirm(adviceNo,
                new AdviceConfirmRequest("c-idem-2", "调度员乙", "ACCEPT_NEW_VERSION", null)))
                .isInstanceOfSatisfying(FloodControlException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ADVICE_ALREADY_CONFIRMED"));

        // KEEP_CURRENT 不下达新指令
        Integer orderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_order", Integer.class);
        assertThat(orderCount).isEqualTo(1);
    }

    @Test
    @DisplayName("维持旧指令并回执后：旧方案留痕 SUPERSEDED、新版本随回执闭环，处置链可归档（终态语义不变）")
    void keepCurrentThenReceiptAndArchive() {
        RollingRoutingView v2 = prepareConflict("keep");
        adviceService.confirm(v2.conflictAdviceNo(),
                new AdviceConfirmRequest("c-keep-1", "调度员甲", "KEEP_CURRENT", "维持当前指令"));

        // 在途旧指令签收→执行→回执（仍按版本 1 方案考核偏差）
        String orderNo = jdbcTemplate.queryForObject(
                "SELECT order_no FROM release_order", String.class);
        flow.sign(orderNo, "值班员甲");
        flow.execute(orderNo, "值班员甲");
        flow.receipt(orderNo, "rc-keep-1", "值班员甲", FlowSupport.safeBoundaryOpenings(), null);

        String oldPlanStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM release_plan WHERE version = 1", String.class);
        assertThat(oldPlanStatus).isEqualTo("SUPERSEDED");
        String newPlanStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM release_plan WHERE version = 2", String.class);
        assertThat(newPlanStatus).isEqualTo("COMPLETED");

        String warningNo = jdbcTemplate.queryForObject(
                "SELECT warning_no FROM warning_chain", String.class);
        var archived = reviewService.archive(new ReviewRequest("rv-keep-1", warningNo,
                "滚动处置复盘"));
        assertThat(archived.status()).isEqualTo("ARCHIVED");
        Integer archivedPlans = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_plan WHERE status = 'ARCHIVED'", Integer.class);
        assertThat(archivedPlans).isEqualTo(2);
    }

    @Test
    @DisplayName("建议不存在 404；确认建议生成来源留痕（requestId 与建议内容）")
    void adviceNotFoundAndTrace() {
        assertThatThrownBy(() -> adviceService.get("JY-NOT-EXIST"))
                .isInstanceOfSatisfying(FloodControlException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        RollingRoutingView v2 = prepareConflict("trace");
        AdviceView advice = adviceService.get(v2.conflictAdviceNo());
        assertThat(advice.status()).isEqualTo("PENDING");
        assertThat(advice.suggestion()).contains("orderPlanVersion")
                .contains("newPlanVersion").contains("recommendation");
        String sourceRequest = jdbcTemplate.queryForObject(
                "SELECT request_id FROM order_conflict_advice WHERE advice_no = ?",
                String.class, v2.conflictAdviceNo());
        assertThat(sourceRequest).isEqualTo("rr-advice-trace");
    }
}
