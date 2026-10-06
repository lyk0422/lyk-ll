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

    /** 掺配比例允许区间含边界判定：5.000% ≤ ratio ≤ 30.000%。 */
    public static boolean withinBlendRatioRange(BigDecimal ratioPct) {
        return ratioPct.compareTo(QualityThresholds.BLEND_RATIO_MIN_PCT) >= 0
                && ratioPct.compareTo(QualityThresholds.BLEND_RATIO_MAX_PCT) <= 0;
    }

    /** 实际掺配偏离目标比例的绝对偏差，保留 4 位小数，单位 %。 */
    public static BigDecimal blendDeviationPct(BigDecimal actualRatioPct, BigDecimal targetRatioPct) {
        return actualRatioPct.subtract(targetRatioPct).abs()
                .setScale(4, RoundingMode.HALF_UP);
    }

    /** 偏差恰 2.0000% 合格；超过才超差。 */
    public static boolean withinBlendTolerance(BigDecimal deviationPct) {
        return deviationPct.compareTo(QualityThresholds.BLEND_DEVIATION_TOLERANCE_PCT) <= 0;
    }

    /** 连续超差采样点达 3 个判失控。 */
    public static boolean isBlendOutOfControl(int consecutiveOutOfTolerance) {
        return consecutiveOutOfTolerance >= QualityThresholds.BLEND_OUT_OF_CONTROL_STREAK;
    }

    /** 比例变更方式判定：相邻版本差值恰 5.0000% 在线直调，超出须降速调整。 */
    public static boolean isDirectBlendAdjust(BigDecimal ratioDeltaPct) {
        return ratioDeltaPct.compareTo(QualityThresholds.BLEND_DIRECT_ADJUST_MAX_DELTA_PCT) <= 0;
    }
}
