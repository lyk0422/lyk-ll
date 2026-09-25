package com.example.starter.wb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;

/**
 * 预配舱单创建请求（首版）。
 */
public record CreateManifestRequest(
        @NotBlank String requestId,
        @NotNull Long flightId,
        @NotNull @PositiveOrZero BigDecimal fuelKg,
        @NotBlank String createdBy,
        @NotEmpty List<LoadItem> items
) {
}
