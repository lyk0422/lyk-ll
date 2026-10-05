package com.example.starter.gas.service;

import com.example.starter.gas.domain.QualitySampleRow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 气质组分核验纯计算逻辑：组分热值折算、同日采样体积加权、相对偏差与 1% 边界判定。
 */
public final class QualityCalculation {

    /** 气质差异阈值：相对偏差恰为 1% 视为一致，超过 1% 才标记差异。 */
    public static final BigDecimal DIFFERENCE_THRESHOLD = new BigDecimal("0.01");

    /** 组分比例合计必须恰为 100%。 */
    public static final BigDecimal RATIO_TOTAL = new BigDecimal("100");

    private static final int HV_SCALE = 6;
    private static final int DEVIATION_SCALE = 8;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private QualityCalculation() {
    }

    /**
     * 单样折算热值：Σ(组分比例% × 组分热值) / 100，保留六位小数（四舍五入）。
     * 调用方须先校验比例合计恰为 100% 且组分已知。
     */
    public static BigDecimal sampleHeatingValue(Map<String, BigDecimal> ratios,
                                                Map<String, BigDecimal> componentHeatingValues) {
        BigDecimal weighted = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : ratios.entrySet()) {
            BigDecimal componentHv = componentHeatingValues.get(entry.getKey());
            if (componentHv == null) {
                throw new IllegalArgumentException("未知组分: " + entry.getKey());
            }
            weighted = weighted.add(entry.getValue().multiply(componentHv));
        }
        return weighted.divide(HUNDRED, HV_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 核验热值：同日采样按采样体积加权，Σ(采样体积×单样热值) / Σ采样体积，保留六位小数。
     */
    public static BigDecimal verifiedHeatingValue(List<QualitySampleRow> samples) {
        BigDecimal totalVolume = BigDecimal.ZERO;
        BigDecimal weightedSum = BigDecimal.ZERO;
        for (QualitySampleRow sample : samples) {
            totalVolume = totalVolume.add(sample.sampleVolume());
            weightedSum = weightedSum.add(sample.sampleVolume().multiply(sample.heatingValue()));
        }
        if (totalVolume.signum() == 0) {
            throw new IllegalStateException("同日采样总体积为 0，无法计算核验热值");
        }
        return weightedSum.divide(totalVolume, HV_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 绝对相对偏差：|核验热值－凭证热值| / 凭证热值，保留八位小数；
     * 凭证热值为零时调用方须拒绝核验。
     */
    public static BigDecimal relativeDeviation(BigDecimal verifiedHeatingValue,
                                               BigDecimal voucherHeatingValue) {
        if (voucherHeatingValue.signum() == 0) {
            throw new IllegalStateException("凭证热值为零，无法计算相对偏差");
        }
        return verifiedHeatingValue.subtract(voucherHeatingValue).abs()
                .divide(voucherHeatingValue, DEVIATION_SCALE, RoundingMode.HALF_UP);
    }

    /** 是否气质差异：相对偏差超过 1%（恰为 1% 视为一致）。 */
    public static boolean isDifference(BigDecimal relativeDeviation) {
        return relativeDeviation.compareTo(DIFFERENCE_THRESHOLD) > 0;
    }
}
