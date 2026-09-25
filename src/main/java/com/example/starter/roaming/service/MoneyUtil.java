package com.example.starter.roaming.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额计算工具：电费金额保留两位小数（元）。
 */
final class MoneyUtil {

    private MoneyUtil() {
    }

    /** 电量×单价后的金额按四舍五入保留到分。 */
    static BigDecimal yuan(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
