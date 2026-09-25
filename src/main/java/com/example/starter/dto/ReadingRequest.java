package com.example.starter.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 振动监测回传请求。sampledAt 为 UTC 采样时刻；velocity 单位 cm/s。
 */
public record ReadingRequest(
        @NotNull Long permitId,
        @NotBlank String pointCode,
        @NotNull Instant sampledAt,
        @NotNull @PositiveOrZero BigDecimal velocity) {
}
