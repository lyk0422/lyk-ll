package com.example.starter.transition.service;

import com.example.starter.transition.QualityThresholds;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 改品种纯业务规则：无状态、无 IO，便于单元测试。
 *
 * <ul>
 *     <li>克重差 ≤10%（恰 10% 含）可直接切换，超出须停机清洗；</li>
 *     <li>灰分等级差 &gt;2 级须冲洗网部，恰 2 级合法直切；</li>
 *     <li>过渡料长度 = 车速(m/min) × 切换时间(s) / 60，单位 m；</li>
 *     <li>关键参数恰在工艺窗口边界视为合格。</li>
 * </ul>
 */
public final class TransitionRules {

    private TransitionRules() {
    }

    /** 克重差百分比 = |to-from|/from×100，保留 4 位小数，单位 %。 */
    public static BigDecimal basisWeightDeltaPct(BigDecimal fromBasisWeight,
                                                 BigDecimal toBasisWeight) {
        return toBasisWeight.subtract(fromBasisWeight).abs()
                .multiply(BigDecimal.valueOf(100))
                .divide(fromBasisWeight, 4, RoundingMode.HALF_UP);
    }

    /** 恰 10.0000% 视为直切。 */
    public static boolean isDirectSwitch(BigDecimal basisWeightDeltaPct) {
        return basisWeightDeltaPct.compareTo(QualityThresholds.DIRECT_SWITCH_MAX_PCT) <= 0;
    }

    /** 灰分等级差绝对值。 */
    public static int ashLevelDelta(int fromAshLevel, int toAshLevel) {
        return Math.abs(toAshLevel - fromAshLevel);
    }

    /** 恰 2 级不冲洗；超过 2 级才冲洗网部。 */
    public static boolean needsWireFlush(int ashLevelDelta) {
        return ashLevelDelta > QualityThresholds.ASH_FLUSH_LEVEL;
    }

    /** 工艺窗口含边界判定：min ≤ value ≤ max。 */
    public static boolean withinWindow(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }

    /** 过渡料长度(m) = 车速(m/min) × 切换时间(s) / 60，保留 3 位小数。 */
    public static BigDecimal transitionLengthM(BigDecimal machineSpeedMMin, int switchTimeSec) {
        return machineSpeedMMin.multiply(BigDecimal.valueOf(switchTimeSec))
                .divide(BigDecimal.valueOf(60), 3, RoundingMode.HALF_UP);
    }

    /** 掺配比例恰在合成允许区间边界合法：min ≤ ratio ≤ max。 */
    public static boolean withinBlendInterval(BigDecimal ratioPct) {
        return withinWindow(ratioPct, QualityThresholds.BLEND_RATIO_MIN_PCT,
                QualityThresholds.BLEND_RATIO_MAX_PCT);
    }

    /** 掺配偏离 = |实测-目标|，保留 3 位小数，单位 百分点。 */
    public static BigDecimal blendDeviationPct(BigDecimal actualRatioPct, BigDecimal targetRatioPct) {
        return actualRatioPct.subtract(targetRatioPct).abs().setScale(3, RoundingMode.HALF_UP);
    }

    /** 偏离恰 2% 合格；超过 2% 判超差。 */
    public static boolean isBlendOutOfTolerance(BigDecimal deviationPct) {
        return deviationPct.compareTo(QualityThresholds.BLEND_DEVIATION_TOLERANCE_PCT) > 0;
    }

    /** 数值最多三位小数（忽略末尾零）：10.000 合法，10.0005 越界。 */
    public static boolean withinThreeDecimals(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 3;
    }

    /**
     * 应投回收量(kg) = 总浆量(kg) × 目标比例(%) / 100，按百分数精确计算，不做展示舍入。
     *
     * @return 精确结果；结果超过三位小数精度时返回 empty，调用方须拒绝该组合
     */
    public static java.util.Optional<BigDecimal> requiredRecycleKg(BigDecimal totalPulpKg,
                                                                   BigDecimal targetRatioPct) {
        BigDecimal required = totalPulpKg.multiply(targetRatioPct).movePointLeft(2);
        return withinThreeDecimals(required) ? java.util.Optional.of(required)
                : java.util.Optional.empty();
    }

    /**
     * 合成正品门槛（恰达边界合格）：灰分 ≤18%，水分 ∈ [4%,8%]，
     * 抗张指数 ≥45 Nm/g，定量与标称克重偏差 ≤3%。
     */
    public static boolean meetsQualityThreshold(BigDecimal ashPct, BigDecimal moisturePct,
                                                BigDecimal tensileIndex,
                                                BigDecimal measuredBasisWeight,
                                                BigDecimal nominalBasisWeight) {
        BigDecimal weightDeviation = measuredBasisWeight.subtract(nominalBasisWeight).abs()
                .multiply(BigDecimal.valueOf(100))
                .divide(nominalBasisWeight, 4, RoundingMode.HALF_UP);
        return ashPct.compareTo(QualityThresholds.ASH_MAX_PCT) <= 0
                && moisturePct.compareTo(QualityThresholds.MOISTURE_MIN_PCT) >= 0
                && moisturePct.compareTo(QualityThresholds.MOISTURE_MAX_PCT) <= 0
                && tensileIndex.compareTo(QualityThresholds.TENSILE_MIN) >= 0
                && weightDeviation.compareTo(QualityThresholds.BASIS_WEIGHT_TOLERANCE_PCT) <= 0;
    }
}
