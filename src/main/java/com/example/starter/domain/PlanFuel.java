package com.example.starter.domain;

import java.math.BigDecimal;

/**
 * 方案燃料明细。氯硫质量分数为百分比数值（0.05 表示 0.05%）。
 */
public record PlanFuel(
        Long id,
        Long planId,
        String fuelName,
        String fuelCategory,
        boolean highClSulfur,
        BigDecimal heatValueMjkg,
        BigDecimal massKg,
        BigDecimal chlorinePct,
        BigDecimal sulfurPct) {

    /** 加权氯质量 kg = 入窑质量 × 氯百分比 / 100。 */
    public BigDecimal chlorineMassKg() {
        return massKg.multiply(chlorinePct).movePointLeft(2);
    }

    /** 加权硫质量 kg = 入窑质量 × 硫百分比 / 100。 */
    public BigDecimal sulfurMassKg() {
        return massKg.multiply(sulfurPct).movePointLeft(2);
    }
}
