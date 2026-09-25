package com.example.starter.reinsurance.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额与分保比例计算工具。所有金额按人民币元、两位小数四舍五入（HALF_UP）。
 */
public final class Money {

    /** 金额精度：分（两位小数）。 */
    public static final int AMOUNT_SCALE = 2;

    /** 比例精度：百万分之一。 */
    public static final int RATE_SCALE = 6;

    private Money() {
    }

    /** 两位小数四舍五入。 */
    public static BigDecimal amount(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        }
        return value.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    /** 非负校验后的金额。 */
    public static BigDecimal requireNonNegative(BigDecimal value, String field) {
        BigDecimal rounded = amount(value);
        if (rounded.signum() < 0) {
            throw new ApiException(400, ErrorCode.VALIDATION_FAILED, field + " 不能为负");
        }
        return rounded;
    }

    /** 比例必须落在 [0,1]。 */
    public static BigDecimal validateRate(BigDecimal rate) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new ApiException(400, ErrorCode.VALIDATION_FAILED, "分保比例必须在 0~1 之间");
        }
        return rate.setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 比例分账：基数 × 比例，两位小数四舍五入。
     */
    public static BigDecimal applyRate(BigDecimal gross, BigDecimal rate) {
        return amount(gross.multiply(rate));
    }

    /**
     * 责任限额截断：分摊金额与剩余可用限额取较小值（恰达限额按限额计入）。
     *
     * @param recovery 按比例计算出的摊回金额
     * @param capacity 该保单限额下剩余可用容量；{@code null} 表示无限额
     * @return 实际计入金额
     */
    public static BigDecimal cap(BigDecimal recovery, BigDecimal capacity) {
        BigDecimal rounded = amount(recovery);
        if (capacity == null) {
            return rounded;
        }
        BigDecimal cap = amount(capacity);
        return rounded.min(cap).max(BigDecimal.ZERO.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP));
    }

    public static boolean isZero(BigDecimal value) {
        return value == null || amount(value).signum() == 0;
    }

    public static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        return amount(amount(a).subtract(amount(b)));
    }

    /**
     * 计算 UTC 日期所属季度标签，格式 {@code yyyy-Qn}（账单按季度、UTC 日界生成）。
     */
    public static String quarterOf(java.time.LocalDate date) {
        int quarter = (date.getMonthValue() - 1) / 3 + 1;
        return date.getYear() + "-Q" + quarter;
    }

    /**
     * 金额按百分位比较的简易格式化（用于明细说明）。
     */
    public static String format(BigDecimal value) {
        return amount(value).toPlainString();
    }
}
