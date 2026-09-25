package com.example.starter.fermentation.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 温度采样读数上报请求，requestId 幂等。
 */
public record TemperatureRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotNull Instant readingAt,
        @NotNull @DecimalMin(value = "-273.15") BigDecimal temperature) {
}
