package com.example.starter.blend;

import com.example.starter.blend.BlendCalculator.ComponentSnapshot;
import com.example.starter.blend.BlendCalculator.Judgment;
import com.example.starter.blend.BlendCalculator.SpecLimits;
import com.example.starter.blend.BlendDtos.Metrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合成调合模型与判定规则单元测试。
 */
class BlendCalculatorTest {

    private final BlendCalculator calculator = new BlendCalculator();

    private static final SpecLimits SPEC = new SpecLimits(
            bd("92.000"), bd("10.000"), bd("65.000"),
            bd("0.300"), bd("0.500"), bd("1.000"));

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void linearWeightedAverageByVolume() {
        Metrics metrics = calculator.computeMetrics(List.of(
                new ComponentSnapshot(bd("60"), bd("95"), bd("5"), bd("60")),
                new ComponentSnapshot(bd("40"), bd("89"), bd("12"), bd("70"))));
        assertEquals(bd("92.600"), metrics.octane());
        assertEquals(bd("7.800"), metrics.sulfur());
        assertEquals(bd("64.000"), metrics.vaporPressure());
    }

    @Test
    void exactLimitIsQualified() {
        // 50/50 混合后辛烷值恰等于上限92.000，蒸气压恰等于上限65.000，均判合格
        Metrics metrics = calculator.computeMetrics(List.of(
                new ComponentSnapshot(bd("50"), bd("95"), bd("5"), bd("60")),
                new ComponentSnapshot(bd("50"), bd("89"), bd("12"), bd("70"))));
        assertEquals(bd("92.000"), metrics.octane());
        assertEquals(bd("65.000"), metrics.vaporPressure());
        Judgment judgment = calculator.judge(metrics, SPEC);
        assertTrue(judgment.qualified());
        assertTrue(judgment.violations().isEmpty());
    }

    @Test
    void overLimitIsUnqualified() {
        Metrics metrics = calculator.computeMetrics(List.of(
                new ComponentSnapshot(bd("80"), bd("95"), bd("5"), bd("60")),
                new ComponentSnapshot(bd("20"), bd("89"), bd("12"), bd("70"))));
        assertEquals(bd("93.800"), metrics.octane());
        Judgment judgment = calculator.judge(metrics, SPEC);
        assertFalse(judgment.qualified());
        assertEquals(1, judgment.violations().size());
        assertTrue(judgment.violations().get(0).contains("辛烷值"));
    }

    @Test
    void belowLimitMinusMarginGivesEdgeHintButStillQualified() {
        // 辛烷值91.400 < 上限92.000 - 安全边际0.300 = 91.700，给出卡边率提示但不判不合格；
        // 硫含量9.800、蒸气压64.500均在安全边际之内，不触发提示
        Metrics metrics = calculator.computeMetrics(List.of(
                new ComponentSnapshot(bd("100"), bd("91.400"), bd("9.800"), bd("64.500"))));
        assertEquals(bd("91.400"), metrics.octane());
        Judgment judgment = calculator.judge(metrics, SPEC);
        assertTrue(judgment.qualified());
        assertEquals(1, judgment.marginHints().size());
        assertTrue(judgment.marginHints().get(0).contains("卡边率"));
        assertTrue(judgment.marginHints().get(0).contains("辛烷值"));
    }

    @Test
    void exactlyAtLimitMinusMarginGivesNoHint() {
        // 恰在上限减安全边际边界（91.700），不触发卡边提示
        Judgment judgment = calculator.judge(
                new Metrics(bd("91.700"), bd("9.500"), bd("64.000")), SPEC);
        assertTrue(judgment.qualified());
        assertTrue(judgment.marginHints().isEmpty());
    }

    @Test
    void emptyComponentsRejected() {
        assertThrows(IllegalArgumentException.class, () -> calculator.computeMetrics(List.of()));
    }
}
