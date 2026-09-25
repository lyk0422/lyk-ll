package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * 偏差考核纯计算单测：3%（含）恰免考，超出部分计费，负向偏差对称。
 */
class AssessmentCalculatorTest {

    private static final BigDecimal NOMINATED = new BigDecimal("10000");
    private static final BigDecimal PRICE = new BigDecimal("0.5");

    @Test
    void exactlyThreePercentIsExempt() {
        // 10000 的 3% 恰为 300，实际 10300 → 偏差绝对值恰 3%，免考
        BigDecimal actual = new BigDecimal("10300");
        assertThat(AssessmentCalculator.isExempt(actual, NOMINATED)).isTrue();
        assertThat(AssessmentCalculator.billableExcess(actual, NOMINATED))
                .isEqualByComparingTo("0");
        assertThat(AssessmentCalculator.penaltyAmount(actual, NOMINATED, PRICE))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void negativeThreePercentIsAlsoExempt() {
        BigDecimal actual = new BigDecimal("9700");
        assertThat(AssessmentCalculator.deviation(actual, NOMINATED)).isEqualByComparingTo("-300");
        assertThat(AssessmentCalculator.isExempt(actual, NOMINATED)).isTrue();
    }

    @Test
    void justBeyondThresholdIsBilledOnExcessOnly() {
        // 实际 12100：偏差 21%，免考 300，计费部分 1800，违约金 1800*0.5=900.00
        BigDecimal actual = new BigDecimal("12100");
        assertThat(AssessmentCalculator.isExempt(actual, NOMINATED)).isFalse();
        assertThat(AssessmentCalculator.deviationRatio(actual, NOMINATED))
                .isEqualByComparingTo("0.210000");
        assertThat(AssessmentCalculator.billableExcess(actual, NOMINATED))
                .isEqualByComparingTo("1800");
        assertThat(AssessmentCalculator.penaltyAmount(actual, NOMINATED, PRICE))
                .isEqualByComparingTo("900.00");
    }

    @Test
    void withinToleranceByOneUnitIsExempt() {
        assertThat(AssessmentCalculator.isExempt(new BigDecimal("10299.99"), NOMINATED)).isTrue();
        assertThat(AssessmentCalculator.isExempt(new BigDecimal("10300.01"), NOMINATED)).isFalse();
    }
}
