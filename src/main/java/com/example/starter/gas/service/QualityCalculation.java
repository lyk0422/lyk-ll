package com.example.starter.gas.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 气质组分核验纯计算逻辑：组分比例折算采样热值、按采样体积加权、绝对相对偏差。
 */
public final class QualityCalculation {

    /** 气质差异阈值：绝对相对偏差恰为 1%（含）视为一致，超过才标记差异。 */
    public static final BigDecimal DIFF_THRESHOLD_RATE = new BigDecimal("0.01");

    /** 组分比例合计必须恰为 100%。 */
    public static final BigDecimal RATIO_TOTAL = new BigDecimal("100");

    private static final int HV_SCALE = 6;
    private static final int RATE_SCALE = 6;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private QualityCalculation() {
    }

    /**
     * 单组采样按组分热值表折算：Σ(比例% ÷ 100 × 组分热值)，保留六位小数（四舍五入）。
     * 调用方须先校验比例合计恰为 100% 且组分编码均存在。
     */
    public static BigDecimal sampleHeatingValue(Map<String, BigDecimal> components,
                                                Map<String, BigDecimal> heatingValueTable) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : components.entrySet()) {
            BigDecimal componentHv = heatingValueTable.get(entry.getKey());
            if (componentHv == null) {
                throw new IllegalArgumentException("未知组分编码: " + entry.getKey());
            }
            sum = sum.add(entry.getValue().multiply(componentHv));
        }
        return sum.divide(HUNDRED, HV_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 核验热值：Σ(采样体积×采样热值) / Σ采样体积，保留六位小数（四舍五入）。
     * 元素为 [采样体积, 采样热值]。
     */
    public static BigDecimal verifiedHeatingValue(List<BigDecimal[]> samples) {
        BigDecimal totalVolume = BigDecimal.ZERO;
        BigDecimal weightedSum = BigDecimal.ZERO;
        for (BigDecimal[] sample : samples) {
            totalVolume = totalVolume.add(sample[0]);
            weightedSum = weightedSum.add(sample[0].multiply(sample[1]));
        }
        if (totalVolume.signum() == 0) {
            throw new IllegalStateException("采样总体积为 0，无法计算核验热值");
        }
        return weightedSum.divide(totalVolume, HV_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 绝对相对偏差＝|核验热值－凭证热值| ÷ 凭证热值，保留六位小数（四舍五入）。
     * 凭证热值为零时不得调用（由服务层拒绝核验）。
     */
    public static BigDecimal deviationRate(BigDecimal verifiedHeatingValue, BigDecimal voucherHeatingValue) {
        if (voucherHeatingValue.signum() == 0) {
            throw new IllegalStateException("凭证热值为零，无法计算相对偏差");
        }
        return verifiedHeatingValue.subtract(voucherHeatingValue).abs()
                .divide(voucherHeatingValue, RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** 偏差恰为 1%（含）视为一致；超过 1% 为气质差异。 */
    public static boolean isQualityDiff(BigDecimal deviationRate) {
        return deviationRate.compareTo(DIFF_THRESHOLD_RATE) > 0;
    }
}
