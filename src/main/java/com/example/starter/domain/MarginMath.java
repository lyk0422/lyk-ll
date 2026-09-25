package com.example.starter.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额与维持担保比例计算工具。金额统一为“分”（long），比例判定使用精确整数比较，
 * 展示比例四舍五入（HALF_UP）到基点，全系统口径一致。
 */
public final class MarginMath {

    /** 追保线：低于 130% 触发（恰好 130% 不触发）。 */
    public static final int CALL_THRESHOLD_PCT = 130;
    /** 直接强平线：低于 110% 跳过追保直接强平（恰好 110% 仍走追保）。 */
    public static final int FORCE_THRESHOLD_PCT = 110;
    /** 恢复线：补足/强平至 140% 以上（含 140%）即停。 */
    public static final int RECOVER_THRESHOLD_PCT = 140;

    private MarginMath() {
    }

    /**
     * 解析金额字符串为分，要求非负且最多两位小数（精确到分，不静默四舍五入客户资金）。
     *
     * @throws IllegalArgumentException 格式非法或精度超过分
     */
    public static long parseCents(String amount) {
        if (amount == null || amount.isBlank()) {
            throw new IllegalArgumentException("金额不能为空");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(amount.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("金额格式非法: " + amount);
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("金额不能为负: " + amount);
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("金额最多精确到分: " + amount);
        }
        return value.movePointRight(2).longValueExact();
    }

    /** 分格式化为元字符串（两位小数）。 */
    public static String formatCents(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }

    /** 比例是否低于 thresholdPct%（精确比较：assets*100 < liabilities*threshold）。 */
    public static boolean below(long assetsCents, long liabilitiesCents, int thresholdPct) {
        return assetsCents * 100 < liabilitiesCents * (long) thresholdPct;
    }

    /** 比例是否达到 thresholdPct% 以上（含等于）。 */
    public static boolean atLeast(long assetsCents, long liabilitiesCents, int thresholdPct) {
        return liabilitiesCents == 0 || assetsCents * 100 >= liabilitiesCents * (long) thresholdPct;
    }

    /**
     * 计算强平需偿还的最小金额（分）：使 (A-x)/(L-x) >= 140% 成立的最小 x，向上取整到分。
     * x >= (140L - 100A) / 40。若负债为 0 或已达标返回 0；若全部平仓仍无法达标返回 L（全部平仓）。
     */
    public static long requiredRepaymentCents(long assetsCents, long liabilitiesCents) {
        if (liabilitiesCents <= 0) {
            return 0;
        }
        long numerator = RECOVER_THRESHOLD_PCT * liabilitiesCents - 100 * assetsCents;
        if (numerator <= 0) {
            return 0;
        }
        long divisor = RECOVER_THRESHOLD_PCT - 100;
        long need = (numerator + divisor - 1) / divisor;
        return Math.min(need, liabilitiesCents);
    }

    /** 比例折算为基点（万分之几），HALF_UP；负债为 0 时返回 null 表示无负债。 */
    public static Integer ratioBp(long assetsCents, long liabilitiesCents) {
        if (liabilitiesCents == 0) {
            return null;
        }
        return BigDecimal.valueOf(assetsCents)
                .multiply(BigDecimal.valueOf(10000))
                .divide(BigDecimal.valueOf(liabilitiesCents), 0, RoundingMode.HALF_UP)
                .intValue();
    }
}
