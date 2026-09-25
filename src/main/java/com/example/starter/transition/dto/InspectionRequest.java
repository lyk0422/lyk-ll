package com.example.starter.transition.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 首次检验提交请求，以 requestId 幂等。
 */
public record InspectionRequest(
        @NotBlank String requestId,
        @NotBlank String rollNo,
        @NotNull BigDecimal ashValuePct,
        @NotNull BigDecimal moisturePct,
        @NotNull BigDecimal tensileIndex,
        @NotNull BigDecimal basisWeightGM2) {
}
