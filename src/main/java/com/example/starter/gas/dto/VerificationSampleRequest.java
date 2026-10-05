package com.example.starter.gas.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * 气质采样记录。
 *
 * @param sampleTime   采样时刻（UTC），按 UTC 气量日归属
 * @param sampleVolume 采样体积（体积单位，>0）
 * @param components   合成组分比例（组分编码 → 百分比），一组合计须恰为 100%
 */
public record VerificationSampleRequest(
        @NotNull Instant sampleTime,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal sampleVolume,
        @NotNull @NotEmpty Map<String, BigDecimal> components) {
}
