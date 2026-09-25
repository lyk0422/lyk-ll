package com.example.starter.dispatch.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 到位判定纯单元测试：偏差不超过 ±2% 判到位，恰在边界判到位。
 */
class PositionEvaluationTest {

    private DispatchService.Deviation evaluate(String target, String actual) {
        return DispatchService.evaluate(new BigDecimal(target), new BigDecimal(actual));
    }

    @Test
    void exactTargetIsInPosition() {
        assertThat(evaluate("50.000", "50.000").inPosition()).isTrue();
    }

    @Test
    void exactlyTwoPercentBoundaryIsInPosition() {
        // 50 的 +2% 恰为 51，边界判到位
        assertThat(evaluate("50.000", "51.000").inPosition()).isTrue();
        // 下侧边界 49 同样判到位
        assertThat(evaluate("50.000", "49.000").inPosition()).isTrue();
    }

    @Test
    void justBeyondTwoPercentIsOutOfPosition() {
        assertThat(evaluate("50.000", "51.001").inPosition()).isFalse();
        assertThat(evaluate("50.000", "48.999").inPosition()).isFalse();
    }

    @Test
    void relativeDeviationUsesTargetAsBase() {
        // 目标 10，实际 10.2：偏差恰 2%，到位
        assertThat(evaluate("10.000", "10.200").inPosition()).isTrue();
        // 目标 10，实际 10.21：偏差 2.1%，超限位并告警
        DispatchService.Deviation deviation = evaluate("10.000", "10.210");
        assertThat(deviation.inPosition()).isFalse();
        assertThat(deviation.displayPercent()).isEqualByComparingTo("2.1");
    }

    @Test
    void zeroTargetRequiresFullyClosed() {
        assertThat(evaluate("0.000", "0.000").inPosition()).isTrue();
        assertThat(evaluate("0.000", "0.001").inPosition()).isFalse();
    }
}
