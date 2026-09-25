package com.example.starter.flood.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 泄量相关口径纯计算：方案总泄量约束与回执偏差判定。
 */
public final class ReleaseMath {

    private ReleaseMath() {
    }

    /**
     * 判断方案总泄量是否合法：不得超过下游安全泄量，恰等合法。
     */
    public static boolean withinSafeLimit(BigDecimal totalRelease, BigDecimal safeRelease) {
        return totalRelease.compareTo(safeRelease) <= 0;
    }

    /**
     * 回执偏差比例 = |实际总泄量 - 方案总泄量| / 方案总泄量，保留 6 位小数（截断后比较仍以精确值为准）。
     *
     * @return 非负偏差比例
     */
    public static BigDecimal deviationRatio(BigDecimal actual, BigDecimal planned) {
        if (planned.compareTo(BigDecimal.ZERO) == 0) {
            return actual.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : BigDecimal.ONE;
        }
        return actual.subtract(planned).abs()
                .divide(planned, 6, RoundingMode.HALF_UP);
    }

    /**
     * 偏差是否一致：偏差比例恰等允许比例（默认 10%）视为一致，超过则须附原因。
     *
     * @param ratio 偏差比例
     * @param allowed 允许比例（含边界）
     */
    public static boolean deviationConsistent(BigDecimal ratio, double allowed) {
        return ratio.compareTo(BigDecimal.valueOf(allowed)) <= 0;
    }
}
