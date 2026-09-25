package com.example.starter.flood;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.flood.service.ReleaseMath;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 泄量约束与回执偏差口径单测：恰等安全泄量合法、偏差恰 10% 一致。
 */
class ReleaseMathTest {

    @Test
    @DisplayName("方案总泄量：小于安全泄量合法、恰等安全泄量合法、超过非法")
    void safeLimitBoundary() {
        BigDecimal safe = new BigDecimal("20.000");
        assertThat(ReleaseMath.withinSafeLimit(new BigDecimal("19.999"), safe)).isTrue();
        assertThat(ReleaseMath.withinSafeLimit(new BigDecimal("20.000"), safe)).isTrue();
        assertThat(ReleaseMath.withinSafeLimit(new BigDecimal("20.001"), safe)).isFalse();
    }

    @Test
    @DisplayName("回执偏差：方案 20、实际 22，偏差恰 10% 视为一致")
    void deviationExactlyTenPercentConsistent() {
        BigDecimal ratio = ReleaseMath.deviationRatio(new BigDecimal("22"), new BigDecimal("20"));
        assertThat(ratio).isEqualByComparingTo("0.100000");
        assertThat(ReleaseMath.deviationConsistent(ratio, 0.10)).isTrue();
    }

    @Test
    @DisplayName("回执偏差：方案 20、实际 22.001，偏差超过 10% 不一致")
    void deviationAboveTenPercentInconsistent() {
        BigDecimal ratio = ReleaseMath.deviationRatio(new BigDecimal("22.001"),
                new BigDecimal("20"));
        assertThat(ratio.compareTo(new BigDecimal("0.10"))).isGreaterThan(0);
        assertThat(ReleaseMath.deviationConsistent(ratio, 0.10)).isFalse();
    }

    @Test
    @DisplayName("回执偏差：实际小于方案同样按绝对差计算")
    void deviationLowerSide() {
        BigDecimal ratio = ReleaseMath.deviationRatio(new BigDecimal("18"), new BigDecimal("20"));
        assertThat(ratio).isEqualByComparingTo("0.100000");
        assertThat(ReleaseMath.deviationConsistent(ratio, 0.10)).isTrue();
    }
}
