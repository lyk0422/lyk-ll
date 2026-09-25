package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 放行请求，携带 requestId、条码与放行时间窗，以 requestId 幂等。
 *
 * @param requestId   幂等键
 * @param barcode     成分条码
 * @param windowStart 放行时间窗起点(UTC，含)
 * @param windowEnd   放行时间窗终点(UTC，含)
 * @param requestTime 请求判定时刻(UTC)，为空时以服务端当前时刻为准（用于效期与时间窗判定）
 */
public record ReleaseRequest(
        @NotBlank String requestId,
        @NotBlank String barcode,
        @NotNull Instant windowStart,
        @NotNull Instant windowEnd,
        Instant requestTime) {
}
