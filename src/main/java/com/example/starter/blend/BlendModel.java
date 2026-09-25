package com.example.starter.blend;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 合成调合模型：辛烷值、硫含量、蒸气压按组分质量线性加权计算。
 * 判定规则：全部指标不超过规格上限判合格，恰等于上限判合格；
 * 配置了安全边际的关键指标，实际值低于（上限 - 安全边际）时给出卡边率提示，但不判不合格。
 */
public final class BlendModel {

    private BlendModel() {
    }

    /** 指标值集合：辛烷值 RON、硫含量 mg/kg、蒸气压 kPa。 */
    public record IndicatorSet(BigDecimal octane, BigDecimal sulfur, BigDecimal rvp) {
    }

    /** 调合组分输入：罐号、切罐量 kg 及该组分的合成化验指标。 */
    public record ComponentInput(String tankCode, BigDecimal qtyKg,
                                 BigDecimal octane, BigDecimal sulfur, BigDecimal rvp) {
    }

    /** 卡边提示：实际值低于（上限 - 安全边际）时输出，卡边率 = 实际值 / 上限 × 100%。 */
    public record EdgeHint(String indicator, BigDecimal actual, BigDecimal limit,
                           BigDecimal margin, BigDecimal edgeRatioPct, String message) {
    }

    /** 规格上限与安全边际视图，与 product_spec 表一一对应。 */
    public record SpecLimits(BigDecimal octaneMax, BigDecimal sulfurMax, BigDecimal rvpMax,
                             BigDecimal octaneMargin, BigDecimal sulfurMargin, BigDecimal rvpMargin) {
    }

    /**
     * 按质量线性加权计算成品指标。硫含量保留 4 位小数，其余保留 3 位，HALF_UP 舍入。
     */
    public static IndicatorSet blend(List<ComponentInput> inputs) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal octaneSum = BigDecimal.ZERO;
        BigDecimal sulfurSum = BigDecimal.ZERO;
        BigDecimal rvpSum = BigDecimal.ZERO;
        for (ComponentInput in : inputs) {
            total = total.add(in.qtyKg());
            octaneSum = octaneSum.add(in.qtyKg().multiply(in.octane()));
            sulfurSum = sulfurSum.add(in.qtyKg().multiply(in.sulfur()));
            rvpSum = rvpSum.add(in.qtyKg().multiply(in.rvp()));
        }
        if (total.signum() <= 0) {
            throw new IllegalArgumentException("调合总量必须为正数");
        }
        return new IndicatorSet(
                octaneSum.divide(total, 3, RoundingMode.HALF_UP),
                sulfurSum.divide(total, 4, RoundingMode.HALF_UP),
                rvpSum.divide(total, 3, RoundingMode.HALF_UP));
    }

    /**
     * 判定是否合格：全部指标不超过规格上限（恰等于上限判合格）。
     */
    public static boolean passes(IndicatorSet ind, SpecLimits spec) {
        return ind.octane().compareTo(spec.octaneMax()) <= 0
                && ind.sulfur().compareTo(spec.sulfurMax()) <= 0
                && ind.rvp().compareTo(spec.rvpMax()) <= 0;
    }

    /**
     * 卡边控制评估：对配置了安全边际（&gt;0）的指标，实际值低于（上限 - 安全边际）时
     * 生成卡边率提示；不影响合格判定。
     */
    public static List<EdgeHint> edgeHints(IndicatorSet ind, SpecLimits spec) {
        List<EdgeHint> hints = new ArrayList<>();
        addHint(hints, "octane", ind.octane(), spec.octaneMax(), spec.octaneMargin());
        addHint(hints, "sulfur", ind.sulfur(), spec.sulfurMax(), spec.sulfurMargin());
        addHint(hints, "rvp", ind.rvp(), spec.rvpMax(), spec.rvpMargin());
        return hints;
    }

    private static void addHint(List<EdgeHint> hints, String name, BigDecimal actual,
                                BigDecimal limit, BigDecimal margin) {
        if (margin == null || margin.signum() <= 0) {
            return;
        }
        BigDecimal edgeFloor = limit.subtract(margin);
        if (actual.compareTo(edgeFloor) < 0) {
            BigDecimal ratio = actual.multiply(BigDecimal.valueOf(100))
                    .divide(limit, 2, RoundingMode.HALF_UP);
            hints.add(new EdgeHint(name, actual, limit, margin, ratio,
                    "指标 " + name + " 实际值 " + actual.stripTrailingZeros().toPlainString()
                            + " 低于卡边控制下限 " + edgeFloor.stripTrailingZeros().toPlainString()
                            + "（上限 " + limit.stripTrailingZeros().toPlainString()
                            + " - 安全边际 " + margin.stripTrailingZeros().toPlainString()
                            + "），卡边率 " + ratio.toPlainString() + "%"));
        }
    }
}
