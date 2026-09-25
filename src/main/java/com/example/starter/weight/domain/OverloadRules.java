package com.example.starter.weight.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 超限判定与分档规则（纯函数，可复算）。
 *
 * <p>超限率 = (实测总重 - 限值) / 限值，保留两位小数（HALF_UP）；
 * 超限率恰为 0 判不超限，大于 0 判超限；
 * 超限率不足 30% 责令卸载放行，30% 及以上罚款并记分，恰 30% 按罚款档处理。
 */
public final class OverloadRules {

    /**
     * 罚款档下限：超限率 30%（含）。
     */
    public static final BigDecimal FINE_THRESHOLD = new BigDecimal("0.30");

    /**
     * 罚款档罚款金额（元）。
     */
    public static final BigDecimal FINE_AMOUNT = new BigDecimal("2000.00");

    /**
     * 罚款档记分数。
     */
    public static final int FINE_POINTS = 3;

    private OverloadRules() {
    }

    /**
     * 计算超限率，保留两位小数（HALF_UP）。
     *
     * @param measuredKg 实测总重（千克）
     * @param limitKg    轴型限值（千克）
     * @return 超限率（如 0.30 表示 30%）
     */
    public static BigDecimal overloadRate(BigDecimal measuredKg, BigDecimal limitKg) {
        if (limitKg == null || limitKg.signum() <= 0) {
            throw new IllegalArgumentException("限值必须为正数");
        }
        return measuredKg.subtract(limitKg).divide(limitKg, 2, RoundingMode.HALF_UP);
    }

    /**
     * 按超限率分档。
     *
     * @param rate 超限率（两位小数）
     * @return 分档结果
     */
    public static DecisionTier tierOf(BigDecimal rate) {
        if (rate.signum() <= 0) {
            return DecisionTier.NO_OVERLOAD;
        }
        if (rate.compareTo(FINE_THRESHOLD) >= 0) {
            return DecisionTier.FINE_POINTS;
        }
        return DecisionTier.UNLOAD_RELEASE;
    }
}
