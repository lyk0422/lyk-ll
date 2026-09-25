package com.example.starter.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 编组核对请求: 与合成运统1 核对辆数/换长/总重。
 */
public record ConsistCheckRequest(
        @NotBlank String requestId,
        @Min(1) int expectedCars,
        @NotNull BigDecimal expectedLength,
        @NotNull BigDecimal expectedWeight) {
}
