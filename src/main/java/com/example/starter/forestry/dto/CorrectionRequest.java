package com.example.starter.forestry.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 蓄积设计值补正请求（仅一次机会，以 requestId 幂等）。
 * 补正值与系统复核蓄积一致则申请进入 SUBMITTED；仍不一致按撤回处理。
 *
 * @param requestId    幂等键
 * @param designVolume 补正后的蓄积设计值，单位立方米
 */
public record CorrectionRequest(
        @NotBlank String requestId,
        @NotNull @Positive BigDecimal designVolume
) {
}
