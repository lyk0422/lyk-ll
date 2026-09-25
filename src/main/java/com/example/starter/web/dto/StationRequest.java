package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 创建基站请求（合成主数据）。
 */
public record StationRequest(
        @NotBlank String stationCode,
        @NotBlank String stationName,
        @NotNull @Positive BigDecimal batteryCapacityWh,
        @NotNull @Positive BigDecimal dischargeRateW
) {
}
