package com.example.starter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 受控指令登记的单个参与机组参数。
 *
 * @param turbineCode       机组编码
 * @param startPowerKw      起始实际功率（kW，整数），须在最低保留与当时可用出力之间
 * @param rampRateKwPerMin  每分钟升降上限（kW/min，正整数）
 * @param durationMinutes   到达期限（分钟，1..15）
 */
public record ControlledTurbineInput(
        @NotBlank String turbineCode,
        @PositiveOrZero int startPowerKw,
        @Positive int rampRateKwPerMin,
        @Min(1) @Max(15) int durationMinutes) {
}
