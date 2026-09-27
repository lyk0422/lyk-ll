package com.example.starter.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 批次系数修订请求：携带 expectedVersion 乐观锁；成功后版本与系数版本各加 1，未投料批准失效。
 */
public record RevisePotencyRequest(
        @NotBlank String requestId,
        @NotNull @Min(1) Integer expectedVersion,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal potency) {
}
