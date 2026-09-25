package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 电池容量抄表上报。按“基站＋抄表时刻”去重，乱序按抄表时刻归位。
 */
public record ReadingRequest(
        @NotBlank String requestId,
        @NotBlank String stationCode,
        @NotNull Instant readAt,
        @NotNull @PositiveOrZero BigDecimal remainingWh
) {
}
