package com.example.starter.media.web.dto;

import com.example.starter.media.domain.LicenseMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 创建授权请求。时间一律为 UTC 时刻，窗口半开 [windowStart, windowEnd)。
 */
public record CreateGrantRequest(
        @NotBlank String requestId,
        @NotBlank String assetId,
        @NotBlank String region,
        @NotBlank String platform,
        @NotNull Instant windowStart,
        @NotNull Instant windowEnd,
        @NotNull LicenseMode mode
) {
}
