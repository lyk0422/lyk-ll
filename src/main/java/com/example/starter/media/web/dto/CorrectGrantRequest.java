package com.example.starter.media.web.dto;

import com.example.starter.media.domain.LicenseMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 授权更正请求：基于 expectedVersionNo 生成新版本，旧版本保留为 SUPERSEDED。
 */
public record CorrectGrantRequest(
        @NotBlank String requestId,
        @NotNull Instant windowStart,
        @NotNull Instant windowEnd,
        @NotNull LicenseMode mode,
        @NotNull @Positive Integer expectedVersionNo
) {
}
