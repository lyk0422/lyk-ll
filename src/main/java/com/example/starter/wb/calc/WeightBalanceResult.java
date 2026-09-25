package com.example.starter.wb.calc;

import java.math.BigDecimal;
import java.util.List;

/**
 * 载重平衡计算结果。
 *
 * @param passengerCount 旅客人数
 * @param payloadKg      总业载，kg
 * @param fuelKg         加油量，kg
 * @param zfwKg          无油重量，kg
 * @param towKg          起飞重量，kg
 * @param zfwMoment      无油总力矩，kg·英寸
 * @param towMoment      起飞总力矩，kg·英寸
 * @param cgZfwPct       无油重心，%MAC
 * @param cgTowPct       起飞重心，%MAC
 * @param withinLimits   是否同时满足重量限制与重心包线（边界合法）
 * @param violations     超限原因列表；为空表示全部合法
 */
public record WeightBalanceResult(
        int passengerCount,
        BigDecimal payloadKg,
        BigDecimal fuelKg,
        BigDecimal zfwKg,
        BigDecimal towKg,
        BigDecimal zfwMoment,
        BigDecimal towMoment,
        BigDecimal cgZfwPct,
        BigDecimal cgTowPct,
        boolean withinLimits,
        List<String> violations
) {
}
