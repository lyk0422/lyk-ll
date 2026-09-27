package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 染料批次登记请求：登记染料代码、正数效价系数（v1）、可用克重及有效截止时刻。
 * 有效期语义 now &lt; expireAt 有效，恰在截止（相等）即失效。
 */
public record RegisterBatchRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotBlank String dyeCode,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal potencyFactor,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal availableG,
        @NotNull OffsetDateTime expireAt,
        @NotNull Integer expectedVersion) {
}
