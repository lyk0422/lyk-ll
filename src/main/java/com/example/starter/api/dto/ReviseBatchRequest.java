package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 批次系数修订请求：在既有批次上发布下一系数版本（factorVersion 加 1）。
 * 修订成功后旧系数版本失效，使未投料补偿批准失效；已投料历史不追改。
 * expectedVersion 为批次登记聚合乐观锁版本，须与当前版本一致。
 */
public record ReviseBatchRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal potencyFactor,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal availableG,
        @NotNull OffsetDateTime expireAt,
        @NotNull Integer expectedVersion) {
}
