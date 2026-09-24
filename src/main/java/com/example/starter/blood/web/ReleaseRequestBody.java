package com.example.starter.blood.web;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 放行请求。
 *
 * @param requestId   幂等键（同键同参重放首次结果，同键异参返回 409）
 * @param barcode     目标成分血条码
 * @param windowStart 放行时间窗起点（UTC，ISO-8601）
 * @param windowEnd   放行时间窗终点（UTC，ISO-8601）
 */
public record ReleaseRequestBody(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String barcode,
        @NotNull Instant windowStart,
        @NotNull Instant windowEnd) {
}
