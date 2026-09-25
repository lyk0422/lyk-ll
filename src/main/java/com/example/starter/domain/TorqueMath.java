package com.example.starter.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 力矩允差计算：实测相对标称的偏差百分比与 ±允差判定。
 *
 * <p>判定基于精确除法与 {@link BigDecimal#compareTo}，不做舍入后比较，
 * 因此恰在 ±边界（如标称 100、允差 5%、实测 95.000 或 105.000）判定为合格。
 */
public final class TorqueMath {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int DEVIATION_SCALE = 4;

    private TorqueMath() {
    }

    /**
     * 偏差百分比 =（实测 - 标称）/ 标称 × 100，正为超扭、负为欠扭；存储展示保留 4 位小数。
     */
    public static BigDecimal deviationPct(BigDecimal nominal, BigDecimal measured) {
        return measured.subtract(nominal)
                .multiply(HUNDRED)
                .divide(nominal, DEVIATION_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 是否在 ±tolerancePct 内（含边界）。
     */
    public static boolean inTolerance(BigDecimal nominal, BigDecimal measured, BigDecimal tolerancePct) {
        // 允许的绝对偏差 = 标称 × 允差 / 100，用高精度比较，边界恰合格。
        BigDecimal allowed = nominal.multiply(tolerancePct)
                .divide(HUNDRED, DEVIATION_SCALE + 4, RoundingMode.HALF_UP);
        return measured.subtract(nominal).abs().compareTo(allowed) <= 0;
    }
}
