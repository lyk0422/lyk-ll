package com.example.starter.gas.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * 气质采样记录：采样时刻（UTC）、采样体积与合成组分比例（合计须恰为 100%）。
 *
 * @param sampleTime   采样时刻（UTC），按 UTC 气量日归属
 * @param sampleVolume 采样体积（体积单位，>0）
 * @param components   合成组分比例：组分编码 → 百分比（各比例 ≥0，合计恰为 100）
 */
public record SampleRequest(
        @NotNull Instant sampleTime,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal sampleVolume,
        @NotEmpty Map<@NotNull String, @NotNull @DecimalMin(value = "0") BigDecimal> components) {
}
