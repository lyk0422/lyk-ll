package com.example.starter.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单条单元温度读数。
 *
 * @param unitCode   单元编码
 * @param supplyTemp 供水温度，单位:℃
 * @param returnTemp 回水温度，单位:℃
 * @param observedAt 温度测量时刻(UTC)
 */
public record TemperatureReadingItem(
        @NotBlank String unitCode,
        @NotNull @DecimalMin(value = "0") BigDecimal supplyTemp,
        @NotNull @DecimalMin(value = "0") BigDecimal returnTemp,
        @NotNull Instant observedAt
) {
}
