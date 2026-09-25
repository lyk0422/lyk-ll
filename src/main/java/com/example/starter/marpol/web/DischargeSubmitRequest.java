package com.example.starter.marpol.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 排放记录提交请求（新建逻辑记录）。
 *
 * @param requestId 幂等键，同键同参返回首次结果，同键异参 409
 */
public record DischargeSubmitRequest(
        @NotBlank String requestId,
        @NotBlank String shipId,
        @NotBlank String voyageNo,
        @NotBlank String dischargeType,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
        @NotNull Instant eventTime,
        @NotNull @Positive BigDecimal quantity,
        @NotBlank String unit) {
}
