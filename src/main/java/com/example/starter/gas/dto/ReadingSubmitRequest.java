package com.example.starter.gas.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 计量读数提交请求。
 *
 * @param requestId    幂等键
 * @param stationCode  交接站编码
 * @param meterTime    表底时刻（UTC），区间读数截止时刻
 * @param volume       本区间交接体积（体积单位，>0）
 * @param heatingValue 本区间热值（能量单位/体积单位，>0）
 */
public record ReadingSubmitRequest(
        @NotBlank String requestId,
        @NotBlank String stationCode,
        @NotNull Instant meterTime,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal volume,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal heatingValue) {
}
