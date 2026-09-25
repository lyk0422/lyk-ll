package com.example.starter.damage.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.starter.damage.api.EstimateDecision;
import com.example.starter.damage.repo.po.DamageItemPo;

class EstimateCalculatorTest {

    private DamageItemPo item(String responsibility, String amount) {
        return new DamageItemPo(1L, 1L, 1L, "IICL-4010", "左前角柱", responsibility,
                "RP-POST", "角柱火焰矫正", new BigDecimal("300.00"), 1,
                new BigDecimal(amount), 0L);
    }

    @Test
    @DisplayName("单项修理费恰为箱值 30%：判定修理")
    void exactlyThirtyPercentIsRepair() {
        var result = EstimateCalculator.calculate(
                List.of(item("OWNER", "300.00")), new BigDecimal("1000.00"));
        assertThat(result.decision()).isEqualTo(EstimateDecision.REPAIR);
        assertThat(result.maxItemAmount()).isEqualByComparingTo("300.00");
        assertThat(result.ratio()).isEqualByComparingTo("0.300000");
    }

    @Test
    @DisplayName("单项修理费超过箱值 30%：判定全损")
    void overThirtyPercentIsTotalLoss() {
        var result = EstimateCalculator.calculate(
                List.of(item("OWNER", "300.01")), new BigDecimal("1000.00"));
        assertThat(result.decision()).isEqualTo(EstimateDecision.TOTAL_LOSS);
        assertThat(result.ratio()).isGreaterThan(new BigDecimal("0.300000"));
    }

    @Test
    @DisplayName("按责任方分别累计修理费")
    void subtotalsByResponsibility() {
        var owner = new DamageItemPo(1L, 1L, 1L, "IICL-2010", "顶板", "OWNER",
                "RP-PATCH", "顶板补焊", new BigDecimal("120.00"), 1, new BigDecimal("120.00"), 0L);
        var terminal = new DamageItemPo(2L, 1L, 1L, "IICL-3010", "门板", "TERMINAL",
                "RP-DOOR", "门板校正", new BigDecimal("150.00"), 2, new BigDecimal("300.00"), 0L);
        var result = EstimateCalculator.calculate(List.of(owner, terminal), new BigDecimal("5000.00"));
        assertThat(result.subtotalOwner()).isEqualByComparingTo("120.00");
        assertThat(result.subtotalTerminal()).isEqualByComparingTo("300.00");
        assertThat(result.totalAmount()).isEqualByComparingTo("420.00");
        assertThat(result.maxItemAmount()).isEqualByComparingTo("300.00");
        assertThat(result.decision()).isEqualTo(EstimateDecision.REPAIR);
    }
}
