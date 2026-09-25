package com.example.starter.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 打小样对色请求；ΔE 不超过 0.8 放行（恰 0.8 合格）。
 */
public record ColorCheckRequest(
        @NotBlank String requestId,
        @NotBlank String sampleId,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal deltaE) {
}
