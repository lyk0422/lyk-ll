package com.example.starter.wb.calc;

import java.math.BigDecimal;

/**
 * 计算用业载项：weightKg 为该站位该类别合计重量（LMC 增量行可为负），作用于给定力臂。
 */
public record CalcLoadItem(
        String category,
        String stationCode,
        int pieces,
        BigDecimal weightKg,
        BigDecimal arm
) {
    public boolean isPassenger() {
        return "PASSENGER".equals(category);
    }

    public BigDecimal moment() {
        return weightKg.multiply(arm);
    }
}
