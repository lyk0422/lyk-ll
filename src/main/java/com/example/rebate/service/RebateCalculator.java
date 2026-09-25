package com.example.rebate.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 退税计算规则（纯函数，便于复算与测试）。
 *
 * <ul>
 *   <li>计税依据 = FOB 价 × 退税率（保留 4 位小数，四舍五入）；</li>
 *   <li>换汇成本 = 计税依据 ÷ FOB 美元价（保留 6 位小数，四舍五入），合法区间 [5.0, 8.0]，恰在端点合法；</li>
 *   <li>到账差异比例 = |到账金额 − 申报税额| ÷ 申报税额（保留 8 位小数），恰 0.5%（0.005）视为一致。</li>
 * </ul>
 */
public final class RebateCalculator {

    /** 换汇成本下限（含）。 */
    public static final BigDecimal EXCHANGE_COST_MIN = new BigDecimal("5.0");

    /** 换汇成本上限（含）。 */
    public static final BigDecimal EXCHANGE_COST_MAX = new BigDecimal("8.0");

    /** 到账差异容忍比例：恰 0.5% 视为一致。 */
    public static final BigDecimal DIFF_TOLERANCE = new BigDecimal("0.005");

    private static final int TAX_SCALE = 4;
    private static final int COST_SCALE = 6;
    private static final int RATIO_SCALE = 8;

    private RebateCalculator() {
    }

    /**
     * 计税依据 = FOB 价 × 退税率。
     */
    public static BigDecimal taxBasis(BigDecimal fobAmount, BigDecimal rebateRate) {
        return fobAmount.multiply(rebateRate).setScale(TAX_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 换汇成本 = 计税依据 ÷ FOB 美元价。
     */
    public static BigDecimal exchangeCost(BigDecimal taxBasis, BigDecimal fobUsd) {
        return taxBasis.divide(fobUsd, COST_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 换汇成本是否在 [5.0, 8.0] 内（含端点）。按 {@code compareTo} 比较，标度差异不影响结果。
     */
    public static boolean exchangeCostInRange(BigDecimal exchangeCost) {
        return exchangeCost.compareTo(EXCHANGE_COST_MIN) >= 0
                && exchangeCost.compareTo(EXCHANGE_COST_MAX) <= 0;
    }

    /**
     * 到账与申报税额差额（绝对值，保留 4 位）。
     */
    public static BigDecimal diffAmount(BigDecimal declaredTax, BigDecimal receivedAmount) {
        return receivedAmount.subtract(declaredTax).abs().setScale(TAX_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 到账差异比例 = |到账 − 申报| ÷ 申报。
     */
    public static BigDecimal diffRatio(BigDecimal declaredTax, BigDecimal receivedAmount) {
        BigDecimal diff = diffAmount(declaredTax, receivedAmount);
        return diff.divide(declaredTax, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 差异是否在容忍范围内：比例不超过 0.005，恰 0.5% 视为一致。
     */
    public static boolean diffConsistent(BigDecimal diffRatio) {
        return diffRatio.compareTo(DIFF_TOLERANCE) <= 0;
    }
}
