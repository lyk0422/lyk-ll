package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 建窗请求。起止时刻为 UTC（ISO-8601），窗口为半开区间 [startAt, endAt)。
 */
public record CreateWindowRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 128) String vesselName,
        @NotNull Long berthId,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 4, fraction = 2) BigDecimal draftM,
        @Min(1) @Max(16) int pilotsRequired) {
}
