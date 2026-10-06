package com.example.starter.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 合成风险评估模型单元测试：分级边界与期限规则。
 */
class RiskEvaluatorTest {

    private final RiskEvaluator evaluator = new RiskEvaluator();

    @Test
    void scoreFormulaIsDeterministic() {
        // (100-35)*0.5 + 18 + 60*0.1 + 15 = 32.5 + 18 + 6 + 15 = 71.5 -> 72
        assertThat(evaluator.score(35, 18.0, 60, true)).isEqualTo(72);
        assertThat(evaluator.score(35, 18.0, 60, true)).isEqualTo(evaluator.score(35, 18.0, 60, true));
    }

    @Test
    void leanAndAgeAreCapped() {
        // 倾斜度超过 30 按 30 计，树龄超过 100 按 100 计
        assertThat(evaluator.score(100, 45.0, 150, false)).isEqualTo(40);
    }

    @Test
    void levelBoundaries() {
        assertThat(evaluator.levelForScore(70)).isEqualTo(1);
        assertThat(evaluator.levelForScore(69)).isEqualTo(2);
        assertThat(evaluator.levelForScore(50)).isEqualTo(2);
        assertThat(evaluator.levelForScore(49)).isEqualTo(3);
        assertThat(evaluator.levelForScore(30)).isEqualTo(3);
        assertThat(evaluator.levelForScore(29)).isEqualTo(4);
    }

    @Test
    void deadlineDaysByLevel() {
        assertThat(evaluator.deadlineDays(1)).isEqualTo(7);
        assertThat(evaluator.deadlineDays(2)).isEqualTo(30);
        assertThat(evaluator.deadlineDays(3)).isEqualTo(90);
        assertThat(evaluator.needsPruning(4)).isFalse();
        assertThatThrownBy(() -> evaluator.deadlineDays(4))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
