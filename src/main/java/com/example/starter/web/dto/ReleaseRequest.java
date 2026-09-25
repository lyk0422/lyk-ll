package com.example.starter.web.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 放行请求。requestId 为幂等键，指纹覆盖 requestId、条码与时间窗。
 *
 * @param requestId   幂等键
 * @param barcode     成分血条码
 * @param windowStart 放行时间窗起点（UTC，ISO-8601）
 * @param windowEnd   放行时间窗终点（UTC，ISO-8601）
 */
public record ReleaseRequest(
        @NotBlank String requestId,
        @NotBlank String barcode,
        @NotNull Instant windowStart,
        @NotNull Instant windowEnd) {
}
