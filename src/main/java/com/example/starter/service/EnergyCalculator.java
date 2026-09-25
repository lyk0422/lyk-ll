package com.example.starter.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.example.starter.persistence.MeterReadingPO;

/**
 * 能量折算：交接体积取气量日窗口内首末表底之差；加权平均热值按相邻表底间的体积增量加权。
 * 能量 = 体积 × 加权平均热值，结果保留两位小数（四舍五入）。
 */
public final class EnergyCalculator {

    /** 热值标度。 */
    public static final int HV_SCALE = 4;
    /** 能量标度（两位小数）。 */
    public static final int ENERGY_SCALE = 2;

    private EnergyCalculator() {
    }

    /**
     * 依据按表底时刻升序归位的读数计算气量日交接体积。
     *
     * @param orderedReadings 气量日窗口内、按 readingTime 升序的读数，至少两条
     * @return 末表底 - 首表底，单位 Nm3
     */
    public static BigDecimal dayVolume(List<MeterReadingPO> orderedReadings) {
        if (orderedReadings == null || orderedReadings.size() < 2) {
            throw new IllegalArgumentException("计算交接体积至少需要两条同一气量日的读数");
        }
        MeterReadingPO first = orderedReadings.get(0);
        MeterReadingPO last = orderedReadings.get(orderedReadings.size() - 1);
        return last.meterIndex().subtract(first.meterIndex());
    }

    /**
     * 加权平均热值：每段体积增量使用该段终点读数的热值加权。
     *
     * @param orderedReadings 按 readingTime 升序的读数，至少两条且总体积增量为正
     * @return 加权平均热值，保留四位小数（四舍五入），单位 MJ/Nm3
     */
    public static BigDecimal weightedHeatingValue(List<MeterReadingPO> orderedReadings) {
        BigDecimal volume = dayVolume(orderedReadings);
        if (volume.signum() <= 0) {
            throw new IllegalArgumentException("表底体积增量必须为正，无法加权热值");
        }
        BigDecimal weightedEnergy = BigDecimal.ZERO;
        for (int i = 1; i < orderedReadings.size(); i++) {
            MeterReadingPO prev = orderedReadings.get(i - 1);
            MeterReadingPO cur = orderedReadings.get(i);
            BigDecimal delta = cur.meterIndex().subtract(prev.meterIndex());
            if (delta.signum() < 0) {
                throw new IllegalArgumentException("表底读数必须单调不减：" + cur.readingTime());
            }
            weightedEnergy = weightedEnergy.add(delta.multiply(cur.heatingValue()));
        }
        return weightedEnergy.divide(volume, HV_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 折算能量 = 体积 × 热值，保留两位小数。
     */
    public static BigDecimal energy(BigDecimal volume, BigDecimal avgHeatingValue) {
        return volume.multiply(avgHeatingValue).setScale(ENERGY_SCALE, RoundingMode.HALF_UP);
    }
}
