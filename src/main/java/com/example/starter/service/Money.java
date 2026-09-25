package com.example.starter.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额计算工具：统一 2 位小数、HALF_UP 舍入，保证账单可逐笔复算。
 */
public final class Money {

    private Money() {
    }

    /** 金额 × 比例，结果保留 2 位小数（元）。 */
    public static BigDecimal times(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /** 规范为 2 位小数。 */
    public static BigDecimal scale(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
