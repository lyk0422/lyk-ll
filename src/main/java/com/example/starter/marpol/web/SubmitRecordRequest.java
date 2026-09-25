package com.example.starter.marpol.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 排放记录提交请求（首版本）。requestId 为幂等键。
 */
public record SubmitRecordRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 32) String shipImo,
        @NotBlank @Size(max = 64) String voyageNo,
        @NotNull @Pattern(regexp = "OIL|GARBAGE|SEWAGE") String type,
        double longitude,
        double latitude,
        @NotNull Instant eventTime,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal quantity,
        @NotBlank @Size(max = 36) String unit) {
}
