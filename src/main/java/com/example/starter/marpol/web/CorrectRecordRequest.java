package com.example.starter.marpol.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 记录修正请求：针对一个版本族生成新版本，业务字段可变更。
 *
 * @param originalRecordId 被修正记录的 id（任意版本均可，服务定位其版本族最新有效版本）
 * @param requestId 本次修正的幂等键
 */
public record CorrectRecordRequest(
        @NotNull Long originalRecordId,
        @NotBlank @Size(max = 64) String requestId,
        @NotNull String type,
        double longitude,
        double latitude,
        @NotNull Instant eventTime,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal quantity,
        @NotBlank @Size(max = 36) String unit) {
}
