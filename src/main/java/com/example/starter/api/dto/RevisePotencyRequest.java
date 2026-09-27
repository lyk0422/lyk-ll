package com.example.starter.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 批次系数修订请求：expectedVersion 须等于批次当前系数版本，成功后版本加 1，未投料补偿批准失效。
 */
public record RevisePotencyRequest(
        @NotBlank String requestId,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal potency,
        @NotNull @Min(1) Integer expectedVersion) {
}
