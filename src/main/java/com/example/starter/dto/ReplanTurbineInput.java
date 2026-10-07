package com.example.starter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 受控分段重规划的单机组新参数（起始功率取当前实际功率，不在请求中给出）。
 *
 * @param turbineCode      机组编码
 * @param rampRateKwPerMin 新的每分钟升降上限（kW/min，正整数）
 * @param durationMinutes  新的到达期限（分钟，1..15）
 */
public record ReplanTurbineInput(
        @NotBlank String turbineCode,
        @Positive int rampRateKwPerMin,
        @Min(1) @Max(15) int durationMinutes) {
}
