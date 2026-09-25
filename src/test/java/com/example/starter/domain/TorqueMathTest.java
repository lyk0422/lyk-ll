package com.example.starter.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 力矩允差边界单元测试：恰在 ±5% 边界合格，超出边界不合格。
 */
class TorqueMathTest {

    private static final BigDecimal NOMINAL = new BigDecimal("100.000");
    private static final BigDecimal TOL = new BigDecimal("5.00");

    @Test
    @DisplayName("恰在下边界 95.000（-5%）判定合格")
    void exactLowerBoundaryPasses() {
        assertThat(TorqueMath.inTolerance(NOMINAL, new BigDecimal("95.000"), TOL)).isTrue();
    }

    @Test
    @DisplayName("恰在上边界 105.000（+5%）判定合格")
    void exactUpperBoundaryPasses() {
        assertThat(TorqueMath.inTolerance(NOMINAL, new BigDecimal("105.000"), TOL)).isTrue();
    }

    @Test
    @DisplayName("低于下边界 94.999 判定超差")
    void belowLowerBoundaryFails() {
        assertThat(TorqueMath.inTolerance(NOMINAL, new BigDecimal("94.999"), TOL)).isFalse();
    }

    @Test
    @DisplayName("高于上边界 105.001 判定超差")
    void aboveUpperBoundaryFails() {
        assertThat(TorqueMath.inTolerance(NOMINAL, new BigDecimal("105.001"), TOL)).isFalse();
    }

    @Test
    @DisplayName("标称值本身合格，偏差为 0")
    void nominalPasses() {
        assertThat(TorqueMath.inTolerance(NOMINAL, NOMINAL, TOL)).isTrue();
        assertThat(TorqueMath.deviationPct(NOMINAL, NOMINAL)).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("非整百分比力矩（100 标称，104.9999 在 5% 内）边界精确")
    void fractionalBoundary() {
        // 允许偏差 = 5；104.9999 在界内
        assertThat(TorqueMath.inTolerance(NOMINAL, new BigDecimal("104.9999"), TOL)).isTrue();
        // 105.0001 出界
        assertThat(TorqueMath.inTolerance(NOMINAL, new BigDecimal("105.0001"), TOL)).isFalse();
    }
}
