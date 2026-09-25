package com.example.starter.dye;

import com.example.starter.dye.service.FeedService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 浴比放大换算与称量偏差门限纯单元测试：恰 0.5% 合法、超过即拒绝。
 */
class FeedMathTest {

    @Test
    void scalesByLiquorRatioAndFabricWeight() {
        // 2.0 g/L × 浴比10 × 100kg = 2000g
        BigDecimal planned = FeedService.plannedGrams(new BigDecimal("2.0000"), 10,
                new BigDecimal("100.00"));
        assertThat(planned).isEqualByComparingTo("2000.000");
    }

    @Test
    void exactlyHalfPercentDeviationIsLegal() {
        // 计划 1000g，实际 1005g（多 0.5%）
        BigDecimal ratio = FeedService.deviationRatio(new BigDecimal("1000.000"),
                new BigDecimal("1005.000"));
        assertThat(ratio).isEqualByComparingTo("0.005000");
        assertThat(ratio.compareTo(FeedService.DEVIATION_LIMIT)).isEqualTo(0);
    }

    @Test
    void negativeHalfPercentDeviationIsLegal() {
        // 少 0.5% 同样合法
        BigDecimal ratio = FeedService.deviationRatio(new BigDecimal("1000.000"),
                new BigDecimal("995.000"));
        assertThat(ratio.compareTo(FeedService.DEVIATION_LIMIT)).isEqualTo(0);
    }

    @Test
    void deviationBeyondHalfPercentRejected() {
        BigDecimal ratio = FeedService.deviationRatio(new BigDecimal("1000.000"),
                new BigDecimal("1005.010"));
        assertThat(ratio.compareTo(FeedService.DEVIATION_LIMIT)).isGreaterThan(0);
    }
}
