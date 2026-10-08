package com.example.starter.water;

import com.example.starter.config.WaterQualityProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阈值评估纯单元测试：覆盖恰等限值正常、严格越限与处置指令映射。
 */
class ThresholdEvaluatorTest {

    private final ThresholdEvaluator evaluator = new ThresholdEvaluator(new WaterQualityProperties());

    @Test
    void exactlyAtLimitsIsNormal() {
        assertTrue(evaluator.evaluate(5.0, 0.5, 18.0).isEmpty());
        assertTrue(evaluator.evaluate(5.0, 0.5, 28.0).isEmpty());
        assertTrue(evaluator.evaluate(5.0, 0.5, 23.0).isEmpty());
    }

    @Test
    void dissolvedOxygenBelowLimitViolates() {
        assertEquals(List.of(ThresholdEvaluator.METRIC_DISSOLVED_OXYGEN),
                evaluator.evaluate(4.999, 0.5, 23.0));
    }

    @Test
    void ammoniaNitrogenAboveLimitViolates() {
        assertEquals(List.of(ThresholdEvaluator.METRIC_AMMONIA_NITROGEN),
                evaluator.evaluate(5.0, 0.501, 23.0));
    }

    @Test
    void temperatureOutOfRangeViolates() {
        assertEquals(List.of(ThresholdEvaluator.METRIC_TEMPERATURE),
                evaluator.evaluate(5.0, 0.5, 17.999));
        assertEquals(List.of(ThresholdEvaluator.METRIC_TEMPERATURE),
                evaluator.evaluate(5.0, 0.5, 28.001));
    }

    @Test
    void multipleViolationsReportedTogether() {
        assertEquals(List.of(ThresholdEvaluator.METRIC_DISSOLVED_OXYGEN,
                        ThresholdEvaluator.METRIC_AMMONIA_NITROGEN, ThresholdEvaluator.METRIC_TEMPERATURE),
                evaluator.evaluate(4.0, 0.6, 30.0));
    }

    @Test
    void disposalTypeMapping() {
        assertEquals(DisposalType.OXYGENATE,
                evaluator.disposalTypeFor(List.of(ThresholdEvaluator.METRIC_DISSOLVED_OXYGEN)));
        assertEquals(DisposalType.WATER_CHANGE,
                evaluator.disposalTypeFor(List.of(ThresholdEvaluator.METRIC_AMMONIA_NITROGEN)));
        assertEquals(DisposalType.ISOLATE,
                evaluator.disposalTypeFor(List.of(ThresholdEvaluator.METRIC_TEMPERATURE)));
    }
}
