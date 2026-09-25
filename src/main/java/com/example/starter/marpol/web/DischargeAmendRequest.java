package com.example.starter.marpol.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 排放记录修正请求：在原记录基础上生成新版本，原版本保留可查。
 *
 * @param requestId       修正请求自身的幂等键
 * @param recordNo        被修正的原逻辑记录号
 * @param basedOnVersion  本次修正所基于的版本；并发修正仅该版本仍为 ACTIVE 时胜出，
 *                        否则返回 409 提示基于最新版本重试
 */
public record DischargeAmendRequest(
        @NotBlank String requestId,
        @NotBlank String recordNo,
        @NotNull Integer basedOnVersion,
        @NotBlank String shipId,
        @NotBlank String voyageNo,
        @NotBlank String dischargeType,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
        @NotNull Instant eventTime,
        @NotNull @Positive BigDecimal quantity,
        @NotBlank String unit) {
}
