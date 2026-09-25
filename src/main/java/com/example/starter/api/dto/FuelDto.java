package com.example.starter.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 方案燃料明细。高氯硫燃料的氯硫按质量加权计入入窑总量。
 *
 * @param fuelName     燃料名称
 * @param fuelCategory 燃料类别（如 RDF、废轮胎、生物质）
 * @param highClSulfur 是否高氯硫燃料；仅 TRUE 的明细参与入窑氯硫加权
 * @param heatValueMjkg 低位热值 MJ/kg
 * @param massKg       计划入窑质量 kg
 * @param chlorinePct  氯质量分数百分比（0.05 表示 0.05%）
 * @param sulfurPct    硫质量分数百分比
 */
public record FuelDto(
        @NotBlank String fuelName,
        @NotBlank String fuelCategory,
        @NotNull Boolean highClSulfur,
        @NotNull @Positive BigDecimal heatValueMjkg,
        @NotNull @Positive BigDecimal massKg,
        @NotNull @DecimalMin("0") BigDecimal chlorinePct,
        @NotNull @DecimalMin("0") BigDecimal sulfurPct) {
}
