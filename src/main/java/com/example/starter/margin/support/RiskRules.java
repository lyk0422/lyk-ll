package com.example.starter.margin.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 风控阈值与金额精度口径。
 * <ul>
 *   <li>维持担保比例 = 总资产 / 总负债；</li>
 *   <li>比例 &lt; 130% 触发追保，恰好 130% 不触发；</li>
 *   <li>补足后须回到 &ge; 140%（含 140%）；</li>
 *   <li>比例 &lt; 110% 跳过追保直接强平，恰好 110% 仍走追保；</li>
 *   <li>强平平到比例恢复至 &gt; 140% 即停（140% 本身继续平，口径为“恢复至 140% 以上”）。</li>
 * </ul>
 * 金额一律精确到分，四舍五入（HALF_UP）；比例保留 4 位小数（万分位），判定时不做四舍五入跨越阈值，
 * 统一用 {@link #ratio(java.math.BigDecimal, java.math.BigDecimal)} 的精确比较。
 */
public final class RiskRules {

    /** 追保触发线（不含）：130% */
    public static final BigDecimal MARGIN_CALL_LINE = new BigDecimal("1.30");
    /** 追保解除/强平停止线：140%。解除含 140%；强平停止要求严格高于 140% */
    public static final BigDecimal MAINTENANCE_LINE = new BigDecimal("1.40");
    /** 直接强平线（不含）：110%，恰好 110% 仍走追保 */
    public static final BigDecimal FORCE_LIQUIDATION_LINE = new BigDecimal("1.10");

    private RiskRules() {
    }

    /**
     * 金额按分四舍五入。
     */
    public static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算维持担保比例，保留 4 位小数（HALF_UP，仅用于展示/落库；阈值判定用精确除法比较）。
     * 负债为 0 时返回 null 表示无负债、不适用比例。
     */
    public static BigDecimal ratio(BigDecimal totalAssets, BigDecimal totalLiabilities) {
        if (totalLiabilities == null || totalLiabilities.signum() == 0) {
            return null;
        }
        return totalAssets.divide(totalLiabilities, 4, RoundingMode.HALF_UP);
    }

    /**
     * 精确阈值比较：assets/liabilities 是否严格小于 threshold（不受比例舍入影响）。
     */
    public static boolean ratioLessThan(BigDecimal assets, BigDecimal liabilities, BigDecimal threshold) {
        return assets.compareTo(threshold.multiply(liabilities)) < 0;
    }

    /**
     * 精确阈值比较：assets/liabilities 是否大于等于 threshold。
     */
    public static boolean ratioAtLeast(BigDecimal assets, BigDecimal liabilities, BigDecimal threshold) {
        return assets.compareTo(threshold.multiply(liabilities)) >= 0;
    }

    /**
     * 精确阈值比较：assets/liabilities 是否严格大于 threshold。
     */
    public static boolean ratioGreaterThan(BigDecimal assets, BigDecimal liabilities, BigDecimal threshold) {
        return assets.compareTo(threshold.multiply(liabilities)) > 0;
    }
}
