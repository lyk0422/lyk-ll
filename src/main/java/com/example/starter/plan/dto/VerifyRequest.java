package com.example.starter.plan.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 首次模体验证请求。
 *
 * @param requestId      幂等请求ID
 * @param measuredDoseGy 模体实测分次剂量，单位Gy；偏差>3%不得执行，恰3%合法
 */
public record VerifyRequest(
        @NotBlank String requestId,
        @NotNull @DecimalMin("0.0") BigDecimal measuredDoseGy) {
}
