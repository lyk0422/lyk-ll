package com.example.starter.penalty;

import java.math.BigDecimal;

/**
 * 处罚分档口径（合成规则，可复算）：
 * 超限率不足 30% 责令卸载放行；30% 及以上罚款并记分，恰 30% 按罚款档；
 * 100% 及以上加重档。
 */
public final class PenaltyTiers {

    public static final String MEASURE_UNLOAD = "UNLOAD";
    public static final String MEASURE_FINE_DEMERIT = "FINE_DEMERIT";

    private static final BigDecimal FINE_THRESHOLD = new BigDecimal("0.30");
    private static final BigDecimal AGGRAVATED_THRESHOLD = new BigDecimal("1.00");

    private PenaltyTiers() {
    }

    /**
     * 按超限率判定处置措施。
     */
    public static String measureFor(BigDecimal overRate) {
        return overRate.compareTo(FINE_THRESHOLD) >= 0 ? MEASURE_FINE_DEMERIT : MEASURE_UNLOAD;
    }

    /**
     * 罚款金额（元）：不足 30% 为 0；30%~100% 为 2000；100% 及以上为 6000。
     */
    public static BigDecimal fineFor(BigDecimal overRate) {
        if (overRate.compareTo(FINE_THRESHOLD) < 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return overRate.compareTo(AGGRAVATED_THRESHOLD) >= 0
                ? new BigDecimal("6000.00") : new BigDecimal("2000.00");
    }

    /**
     * 记分数：不足 30% 为 0；30%~100% 记 3 分；100% 及以上记 6 分。
     */
    public static int pointsFor(BigDecimal overRate) {
        if (overRate.compareTo(FINE_THRESHOLD) < 0) {
            return 0;
        }
        return overRate.compareTo(AGGRAVATED_THRESHOLD) >= 0 ? 6 : 3;
    }
}
