package com.example.starter.berth;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建靠泊窗口申请请求。起止时刻为 UTC ISO-8601  instant。
 */
public record CreateBerthWindowRequest(
        @NotBlank String requestId,
        @NotBlank String vesselName,
        @NotNull Long berthId,
        @NotNull Instant startTime,
        @NotNull Instant endTime,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal draft,
        @Min(1) int pilotsRequired) {
}
