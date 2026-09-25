package com.example.starter.flood;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.flood.exception.FloodControlException;
import com.example.starter.flood.service.OrderService;
import com.example.starter.flood.web.dto.OpeningItem;
import com.example.starter.flood.web.dto.OrderView;
import com.example.starter.flood.web.dto.PlanView;
import com.example.starter.flood.web.dto.RoutingView;
import com.example.starter.flood.web.dto.WarningView;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 泄流指令链 H2 集成测试：
 * 时限内签收、恰满 15 分钟超时升级、执行前置校验、回执偏差恰 10%/超阈原因必填、回执幂等。
 */
class OrderIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private FlowSupport flow;

    private PlanView issuedPlan() {
        WarningView warning = flow.openWarning("w-order-" + clock.instant().toEpochMilli());
        RoutingView routing = flow.route("r-order-" + clock.instant().toEpochMilli(),
                warning.warningNo(), new BigDecimal("200.0000"), new BigDecimal("400001"),
                new BigDecimal("150"), new BigDecimal("10"));
        return flow.createPlan("p-order-" + clock.instant().toEpochMilli(),
                warning.warningNo(), routing.routingNo(), FlowSupport.safeBoundaryOpenings());
    }

    @Test
    @DisplayName("签收时限内正常签收→执行→回执闭环")
    void normalChain() {
        PlanView plan = issuedPlan();
        clock.advanceMinutes(14);
        OrderView signed = orderService.get(plan.orderNo());
        flow.sign(plan.orderNo(), "值班员甲");
        flow.execute(plan.orderNo(), "值班员甲");
        flow.receipt(plan.orderNo(), "rc-normal", "值班员甲",
                FlowSupport.safeBoundaryOpenings(), null);
        OrderView done = orderService.get(plan.orderNo());
        assertThat(done.status()).isEqualTo("RECEIPTED");
        assertThat(done.deviationRatio()).isEqualByComparingTo("0.000000");
        assertThat(signed.orderNo()).isEqualTo(plan.orderNo());
    }

    @Test
    @DisplayName("恰满 15 分钟未签收：签收动作自动升级值班领导并留痕")
    void signExactlyAtDeadlineEscalates() {
        PlanView plan = issuedPlan();
        // 下达时刻 + 15 分钟（恰满，含边界）
        clock.advanceMinutes(15);
        flow.sign(plan.orderNo(), "值班员乙");
        OrderView view = orderService.get(plan.orderNo());
        assertThat(view.status()).isEqualTo("ESCALATED");
        assertThat(view.escalatedTo()).isEqualTo("值班领导");
        assertThat(view.escalatedAt()).isEqualTo(view.signDeadline());
        assertThat(view.signer()).isEqualTo("值班员乙");
        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM order_event WHERE order_id = "
                        + "(SELECT id FROM release_order WHERE order_no = ?) AND event_type = 'ESCALATED'",
                Integer.class, plan.orderNo());
        assertThat(eventCount).isEqualTo(1);
    }

    @Test
    @DisplayName("超时巡检：恰满 15 分钟自动升级，未超时不升级；升级后仍可执行回执")
    void timeoutCheckBoundary() {
        PlanView plan = issuedPlan();
        clock.advanceMinutes(14);
        assertThat(orderService.escalateIfTimeout(plan.orderNo())).isFalse();
        clock.advanceMinutes(1);
        assertThat(orderService.escalateIfTimeout(plan.orderNo())).isTrue();
        // 已升级后重复巡检幂等：不重复升级
        assertThat(orderService.escalateIfTimeout(plan.orderNo())).isFalse();
        OrderView view = orderService.get(plan.orderNo());
        assertThat(view.status()).isEqualTo("ESCALATED");

        // 升级态需补签收后执行
        flow.sign(plan.orderNo(), "值班领导代签");
        flow.execute(plan.orderNo(), "值班员丙");
        flow.receipt(plan.orderNo(), "rc-timeout", "值班员丙",
                FlowSupport.safeBoundaryOpenings(), null);
        assertThat(orderService.get(plan.orderNo()).status()).isEqualTo("RECEIPTED");
    }

    @Test
    @DisplayName("未签收直接执行：拒绝")
    void executeWithoutSignRejected() {
        PlanView plan = issuedPlan();
        assertThatThrownBy(() -> flow.execute(plan.orderNo(), "值班员甲"))
                .isInstanceOf(FloodControlException.class)
                .hasMessageContaining("需先签收");
    }

    @Test
    @DisplayName("回执偏差恰 10%（实际 22 vs 方案 20）：视为一致，无需原因")
    void receiptDeviationExactlyTenPercentOk() {
        PlanView plan = issuedPlan();
        flow.sign(plan.orderNo(), "值班员甲");
        flow.execute(plan.orderNo(), "值班员甲");
        List<OpeningItem> actual = List.of(new OpeningItem("1#排洪隧洞工作闸门",
                new BigDecimal("1.300"), new BigDecimal("22.000")));
        flow.receipt(plan.orderNo(), "rc-10pct", "值班员甲", actual, null);
        OrderView view = orderService.get(plan.orderNo());
        assertThat(view.status()).isEqualTo("RECEIPTED");
        assertThat(view.deviationRatio()).isEqualByComparingTo("0.100000");
        assertThat(view.deviationReason()).isNull();
        assertThat(view.actualTotalRelease()).isEqualByComparingTo("22.000");
    }

    @Test
    @DisplayName("回执偏差超过 10%（实际 22.001）：无原因拒绝，附原因后受理")
    void receiptDeviationOverTenPercentRequiresReason() {
        PlanView plan = issuedPlan();
        flow.sign(plan.orderNo(), "值班员甲");
        flow.execute(plan.orderNo(), "值班员甲");
        List<OpeningItem> actual = List.of(new OpeningItem("1#排洪隧洞工作闸门",
                new BigDecimal("1.310"), new BigDecimal("22.001")));
        assertThatThrownBy(() -> flow.receipt(plan.orderNo(), "rc-over", "值班员甲",
                actual, null))
                .isInstanceOf(FloodControlException.class)
                .hasMessageContaining("必须附原因说明");

        flow.receipt(plan.orderNo(), "rc-over", "值班员甲", actual,
                "上游洪峰叠加，按调度令加大开度 0.11m");
        OrderView view = orderService.get(plan.orderNo());
        assertThat(view.status()).isEqualTo("RECEIPTED");
        assertThat(view.deviationRatio().compareTo(new BigDecimal("0.10"))).isGreaterThan(0);
        assertThat(view.deviationReason()).contains("洪峰叠加");
    }

    @Test
    @DisplayName("回执幂等：同键同参重放返回首次回执，异参 409")
    void receiptIdempotency() {
        PlanView plan = issuedPlan();
        flow.sign(plan.orderNo(), "值班员甲");
        flow.execute(plan.orderNo(), "值班员甲");
        List<OpeningItem> actual = List.of(new OpeningItem("1#排洪隧洞工作闸门",
                new BigDecimal("1.300"), new BigDecimal("22.000")));
        flow.receipt(plan.orderNo(), "rc-idem", "值班员甲", actual, null);
        // 同键重放
        orderService.receipt(plan.orderNo(),
                new com.example.starter.flood.web.dto.ReceiptRequest("rc-idem", "值班员甲",
                        List.of(new OpeningItem("1#排洪隧洞工作闸门",
                                new BigDecimal("1.3"), new BigDecimal("22"))), null));
        OrderView view = orderService.get(plan.orderNo());
        assertThat(view.actualTotalRelease()).isEqualByComparingTo("22.000");
        // 异参冲突
        assertThatThrownBy(() -> orderService.receipt(plan.orderNo(),
                new com.example.starter.flood.web.dto.ReceiptRequest("rc-idem", "值班员甲",
                        List.of(new OpeningItem("1#排洪隧洞工作闸门",
                                new BigDecimal("1.3"), new BigDecimal("22.001"))), null)))
                .isInstanceOf(FloodControlException.class)
                .hasMessageContaining("不同参数的回执");
    }
}
