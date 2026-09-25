package com.example.starter.gas.service;

import com.example.starter.gas.domain.MeterReadingRow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 气量与偏差考核纯计算逻辑：能量＝体积×加权平均热值，保留两位小数。
 */
public final class GasCalculation {

    /** 免考偏差比例：日指定能量的 3%（含边界）。 */
    public static final BigDecimal TOLERANCE_RATE = new BigDecimal("0.03");

    private static final int ENERGY_SCALE = 2;
    private static final int HV_SCALE = 6;

    private GasCalculation() {
    }

    /**
     * 按体积加权平均热值：Σ(区间体积×区间热值) / Σ区间体积。
     */
    public static BigDecimal weightedHeatingValue(List<MeterReadingRow> readings) {
        BigDecimal totalVolume = BigDecimal.ZERO;
        BigDecimal weightedSum = BigDecimal.ZERO;
        for (MeterReadingRow reading : readings) {
            totalVolume = totalVolume.add(reading.volume());
            weightedSum = weightedSum.add(reading.volume().multiply(reading.heatingValue()));
        }
        if (totalVolume.signum() == 0) {
            throw new IllegalStateException("读数总体积为 0，无法计算加权热值");
        }
        return weightedSum.divide(totalVolume, HV_SCALE, RoundingMode.HALF_UP);
    }

    /** 折算能量＝体积×热值，保留两位小数（四舍五入）。 */
    public static BigDecimal energy(BigDecimal volume, BigDecimal weightedHeatingValue) {
        return volume.multiply(weightedHeatingValue).setScale(ENERGY_SCALE, RoundingMode.HALF_UP);
    }

    /** 偏差＝实际交接能量－日指定能量。 */
    public static BigDecimal deviation(BigDecimal actualEnergy, BigDecimal nominatedEnergy) {
        return actualEnergy.subtract(nominatedEnergy).setScale(ENERGY_SCALE, RoundingMode.HALF_UP);
    }

    /** 3% 免考阈值能量（含边界），保留两位小数。 */
    public static BigDecimal toleranceEnergy(BigDecimal nominatedEnergy) {
        return nominatedEnergy.multiply(TOLERANCE_RATE).setScale(ENERGY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 违约金：偏差绝对值不超过阈值（含）免考为 0；
     * 超出部分（|偏差|－阈值）×约定单价，保留两位小数。
     */
    public static BigDecimal penalty(BigDecimal deviationAbs, BigDecimal tolerance,
                                     BigDecimal unitPrice) {
        if (deviationAbs.compareTo(tolerance) <= 0) {
            return BigDecimal.ZERO.setScale(ENERGY_SCALE, RoundingMode.HALF_UP);
        }
        return deviationAbs.subtract(tolerance).multiply(unitPrice)
                .setScale(ENERGY_SCALE, RoundingMode.HALF_UP);
    }
}
