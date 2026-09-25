package com.example.starter.weight.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 超限率计算与分档口径单元测试：恰 0 不超限、恰 30% 落罚款档、不足 30% 卸载放行。
 */
class OverloadRulesTest {

    private static final BigDecimal LIMIT = new BigDecimal("31000.00");

    @Test
    @DisplayName("实测恰为限值：超限率 0.00，判不超限")
    void exactlyAtLimitIsNotOverload() {
        BigDecimal rate = OverloadRules.overloadRate(new BigDecimal("31000.00"), LIMIT);
        assertThat(rate).isEqualByComparingTo("0.00");
        assertThat(OverloadRules.tierOf(rate)).isEqualTo(DecisionTier.NO_OVERLOAD);
    }

    @Test
    @DisplayName("实测低于限值：超限率为负，判不超限")
    void belowLimitIsNotOverload() {
        BigDecimal rate = OverloadRules.overloadRate(new BigDecimal("30000.00"), LIMIT);
        assertThat(rate).isEqualByComparingTo("-0.03");
        assertThat(OverloadRules.tierOf(rate)).isEqualTo(DecisionTier.NO_OVERLOAD);
    }

    @Test
    @DisplayName("超限率恰为 30%：按罚款记分档处理")
    void exactlyThirtyPercentFallsIntoFineTier() {
        // 40300 = 31000 * 1.30，超限率恰为 0.30
        BigDecimal rate = OverloadRules.overloadRate(new BigDecimal("40300.00"), LIMIT);
        assertThat(rate).isEqualByComparingTo("0.30");
        assertThat(OverloadRules.tierOf(rate)).isEqualTo(DecisionTier.FINE_POINTS);
    }

    @Test
    @DisplayName("超限率不足 30%：责令卸载放行")
    void belowThirtyPercentIsUnloadTier() {
        // 39990 - 31000 = 8990，8990/31000 = 0.29
        BigDecimal rate = OverloadRules.overloadRate(new BigDecimal("39990.00"), LIMIT);
        assertThat(rate).isEqualByComparingTo("0.29");
        assertThat(OverloadRules.tierOf(rate)).isEqualTo(DecisionTier.UNLOAD_RELEASE);
    }

    @Test
    @DisplayName("超限率高于 30%：罚款记分档")
    void aboveThirtyPercentIsFineTier() {
        BigDecimal rate = OverloadRules.overloadRate(new BigDecimal("40920.00"), LIMIT);
        assertThat(rate).isEqualByComparingTo("0.32");
        assertThat(OverloadRules.tierOf(rate)).isEqualTo(DecisionTier.FINE_POINTS);
    }

    @Test
    @DisplayName("超限率保留两位小数（HALF_UP），分档按舍入后口径")
    void rateRoundedHalfUpToTwoDecimals() {
        // 构造真实值 0.295... 的场景：31000 * 1.295 = 40145，9145/31000 = 0.2950 → 0.30 落罚款档
        BigDecimal rate = OverloadRules.overloadRate(new BigDecimal("40145.00"), LIMIT);
        assertThat(rate).isEqualByComparingTo("0.30");
        assertThat(OverloadRules.tierOf(rate)).isEqualTo(DecisionTier.FINE_POINTS);
    }

    @Test
    @DisplayName("限值非正数时拒绝计算")
    void invalidLimitRejected() {
        assertThatThrownBy(() -> OverloadRules.overloadRate(BigDecimal.TEN, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
