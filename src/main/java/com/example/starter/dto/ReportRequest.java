package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/**
 * 机组执行回报请求。
 *
 * @param requestId  幂等键，覆盖全部业务字段
 * @param turbineCode 机组编码
 * @param measuredAt 出力时刻（ISO-8601，如 2026-09-26T10:10:00Z）
 * @param powerKw    实测出力（kW）
 */
public record ReportRequest(
        @NotBlank String requestId,
        @NotBlank String turbineCode,
        @NotNull Instant measuredAt,
        @PositiveOrZero int powerKw) {
}
