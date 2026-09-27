package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 染料批次登记请求：染料代码、正数效价系数、可用克重与有效截止（精确时刻，参与幂等指纹）。
 */
public record RegisterDyeLotRequest(
        @NotBlank String requestId,
        @NotBlank String lotCode,
        @NotBlank String dyeCode,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal potency,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal availableG,
        @NotNull Instant validUntil) {
}
