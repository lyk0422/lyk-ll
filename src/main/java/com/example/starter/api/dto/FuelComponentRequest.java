package com.example.starter.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 入窑燃料组分项。
 *
 * @param fuelName          燃料名称（合成数据）
 * @param alternative       是否为替代燃料
 * @param feedRateKgH       入窑投料量，单位 kg/h
 * @param heatValueKcalKg   低位热值，单位 kcal/kg
 * @param chlorineContentPct 氯质量分数，单位 %（如 0.05 表示 0.05%）
 * @param sulfurContentPct  硫质量分数，单位 %
 */
public record FuelComponentRequest(
        @NotBlank String fuelName,
        @NotNull Boolean alternative,
        @NotNull @DecimalMin("0.0000") BigDecimal feedRateKgH,
        @NotNull @DecimalMin("0.0000") BigDecimal heatValueKcalKg,
        @NotNull @DecimalMin("0.0000") BigDecimal chlorineContentPct,
        @NotNull @DecimalMin("0.0000") BigDecimal sulfurContentPct) {
}
