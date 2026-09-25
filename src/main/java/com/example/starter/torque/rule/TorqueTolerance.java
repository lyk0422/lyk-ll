package com.example.starter.torque.rule;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 力矩公差规则：关键紧固件实测力矩须在工艺卡标称值 ±5% 内，恰在边界合格。
 * 采用精确十进制比较，避免浮点误差。
 */
public final class TorqueTolerance {

    /** 公差比例 5%。 */
    public static final BigDecimal TOLERANCE = new BigDecimal("0.05");

    private TorqueTolerance() {
    }

    /** 允许下限（含）：标称值 × 0.95。 */
    public static BigDecimal lowerBound(BigDecimal nominal) {
        return nominal.multiply(BigDecimal.ONE.subtract(TOLERANCE))
                .setScale(3, RoundingMode.HALF_UP);
    }

    /** 允许上限（含）：标称值 × 1.05。 */
    public static BigDecimal upperBound(BigDecimal nominal) {
        return nominal.multiply(BigDecimal.ONE.add(TOLERANCE))
                .setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * 是否合格：下限 ≤ 实测 ≤ 上限，恰在边界（等于 0.95 或 1.05 倍标称值）判定合格。
     */
    public static boolean isWithin(BigDecimal measured, BigDecimal nominal) {
        BigDecimal lower = lowerBound(nominal);
        BigDecimal upper = upperBound(nominal);
        return measured.compareTo(lower) >= 0 && measured.compareTo(upper) <= 0;
    }
}
