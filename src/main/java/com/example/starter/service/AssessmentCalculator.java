package com.example.starter.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 逐日偏差考核纯计算：偏差绝对值不超过日指定 3%（含）免考，超出部分按单价计违约金。
 */
public final class AssessmentCalculator {

    /** 免考阈值比例：3%。 */
    public static final BigDecimal TOLERANCE = new BigDecimal("0.03");
    private static final int RATIO_SCALE = 6;
    private static final int AMOUNT_SCALE = 2;

    private AssessmentCalculator() {
    }

    /** 偏差能量（实际 - 指定）。 */
    public static BigDecimal deviation(BigDecimal actualEnergy, BigDecimal nominatedEnergy) {
        return actualEnergy.subtract(nominatedEnergy);
    }

    /** 偏差比例 = |实际-指定| / 指定，保留六位小数。 */
    public static BigDecimal deviationRatio(BigDecimal actualEnergy, BigDecimal nominatedEnergy) {
        BigDecimal abs = deviation(actualEnergy, nominatedEnergy).abs();
        return abs.divide(nominatedEnergy, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 是否免考：|偏差| ≤ 指定 × 3%（恰为 3% 免考，compareTo 比较，容差 0.000001 MJ 防舍入抖动）。
     */
    public static boolean isExempt(BigDecimal actualEnergy, BigDecimal nominatedEnergy) {
        BigDecimal abs = deviation(actualEnergy, nominatedEnergy).abs();
        BigDecimal threshold = nominatedEnergy.multiply(TOLERANCE);
        return abs.compareTo(threshold) <= 0;
    }

    /**
     * 计费偏差能量 = max(0, |偏差| - 指定×3%)；免考时为 0。
     */
    public static BigDecimal billableExcess(BigDecimal actualEnergy, BigDecimal nominatedEnergy) {
        BigDecimal abs = deviation(actualEnergy, nominatedEnergy).abs();
        BigDecimal threshold = nominatedEnergy.multiply(TOLERANCE);
        BigDecimal excess = abs.subtract(threshold);
        return excess.signum() > 0 ? excess : BigDecimal.ZERO;
    }

    /**
     * 违约金 = 计费偏差能量 × 单价，保留两位小数；免考时为 0.00。
     */
    public static BigDecimal penaltyAmount(BigDecimal actualEnergy, BigDecimal nominatedEnergy,
            BigDecimal unitPrice) {
        if (isExempt(actualEnergy, nominatedEnergy)) {
            return BigDecimal.ZERO.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        }
        return billableExcess(actualEnergy, nominatedEnergy).multiply(unitPrice)
                .setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }
}
