package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 染料批次登记请求：染料代码、正数效价系数、可用克重与有效截止（精确时刻，ISO-8601）。
 * 效价仅按线性模型使用：补偿目标=原目标/系数；now&lt;截止有效，恰在截止失效。
 */
public record RegisterBatchRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotBlank String dyeCode,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal factor,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal availableG,
        @NotNull Instant validUntil) {
}
