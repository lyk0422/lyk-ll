package com.example.starter.wbb.po;

import java.math.BigDecimal;

/**
 * 机型合成性能数据：最大无油重量与使用空重。
 */
public record AircraftConfig(
        Long id,
        String aircraftType,
        BigDecimal maxZeroFuelWeightKg,
        BigDecimal operatingEmptyWeightKg,
        BigDecimal operatingEmptyMoment,
        BigDecimal defaultFuelArm
) {
}
