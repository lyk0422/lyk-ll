package com.example.starter.blend;

import com.example.starter.blend.BlendDtos.Metrics;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 合成调合模型：辛烷值、硫含量、蒸气压按组分体积线性加权计算。
 * 判定规则：实际值小于等于规格上限判合格（恰等于上限判合格）；
 * 实际值低于上限减安全边际时给出卡边率提示，但不判不合格。
 */
@Component
public class BlendCalculator {

    /** 指标保留小数位。 */
    public static final int METRIC_SCALE = 3;

    /**
     * 组分快照（切罐时的量与质量指标）。
     */
    public record ComponentSnapshot(BigDecimal volume, BigDecimal octane,
                                    BigDecimal sulfur, BigDecimal vaporPressure) {
    }

    /**
     * 规格上限与安全边际。
     */
    public record SpecLimits(BigDecimal octaneLimit, BigDecimal sulfurLimit, BigDecimal vaporPressureLimit,
                             BigDecimal octaneMargin, BigDecimal sulfurMargin, BigDecimal vaporPressureMargin) {
    }

    /**
     * 判定结果：qualified 是否合格；violations 超限项；marginHints 卡边率提示。
     */
    public record Judgment(boolean qualified, List<String> violations, List<String> marginHints) {
    }

    /**
     * 按体积线性加权计算成品指标，结果保留 {@link #METRIC_SCALE} 位小数（HALF_UP）。
     */
    public Metrics computeMetrics(List<ComponentSnapshot> components) {
        if (components == null || components.isEmpty()) {
            throw new IllegalArgumentException("组分列表不能为空");
        }
        BigDecimal totalVolume = BigDecimal.ZERO;
        BigDecimal octaneSum = BigDecimal.ZERO;
        BigDecimal sulfurSum = BigDecimal.ZERO;
        BigDecimal vaporSum = BigDecimal.ZERO;
        for (ComponentSnapshot c : components) {
            totalVolume = totalVolume.add(c.volume());
            octaneSum = octaneSum.add(c.volume().multiply(c.octane()));
            sulfurSum = sulfurSum.add(c.volume().multiply(c.sulfur()));
            vaporSum = vaporSum.add(c.volume().multiply(c.vaporPressure()));
        }
        if (totalVolume.signum() <= 0) {
            throw new IllegalArgumentException("调合总量必须为正");
        }
        return new Metrics(
                octaneSum.divide(totalVolume, METRIC_SCALE, RoundingMode.HALF_UP),
                sulfurSum.divide(totalVolume, METRIC_SCALE, RoundingMode.HALF_UP),
                vaporSum.divide(totalVolume, METRIC_SCALE, RoundingMode.HALF_UP));
    }

    /**
     * 按规格判定：任一指标超过上限则不合格；低于上限减安全边际时追加卡边率提示。
     */
    public Judgment judge(Metrics metrics, SpecLimits spec) {
        List<String> violations = new ArrayList<>();
        List<String> hints = new ArrayList<>();
        checkMetric("辛烷值", metrics.octane(), spec.octaneLimit(), spec.octaneMargin(), violations, hints);
        checkMetric("硫含量", metrics.sulfur(), spec.sulfurLimit(), spec.sulfurMargin(), violations, hints);
        checkMetric("蒸气压", metrics.vaporPressure(), spec.vaporPressureLimit(), spec.vaporPressureMargin(),
                violations, hints);
        return new Judgment(violations.isEmpty(), List.copyOf(violations), List.copyOf(hints));
    }

    /**
     * 单指标判定：actual &gt; limit 超限不合格；actual &lt; limit - margin 追加卡边率提示（不判不合格）。
     */
    private void checkMetric(String name, BigDecimal actual, BigDecimal limit, BigDecimal margin,
                             List<String> violations, List<String> hints) {
        if (actual.compareTo(limit) > 0) {
            violations.add(name + "实际值" + actual.toPlainString() + "超过规格上限" + limit.toPlainString());
            return;
        }
        if (margin != null && margin.signum() > 0
                && actual.compareTo(limit.subtract(margin)) < 0) {
            BigDecimal edgeRate = actual.divide(limit, 4, RoundingMode.HALF_UP);
            hints.add(name + "卡边率" + edgeRate.toPlainString()
                    + "，低于上限减安全边际" + limit.subtract(margin).toPlainString()
                    + "，建议卡边控制（不判不合格）");
        }
    }
}
