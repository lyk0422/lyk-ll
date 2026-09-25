package com.example.starter.transition.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 复议提交请求，以 requestId 幂等；每卷至多受理一次。
 */
public record ReexamRequest(
        @NotBlank String requestId,
        @NotBlank String rollNo,
        @NotNull BigDecimal ashValuePct,
        @NotNull BigDecimal moisturePct,
        @NotNull BigDecimal tensileIndex,
        @NotNull BigDecimal basisWeightGM2) {
}
