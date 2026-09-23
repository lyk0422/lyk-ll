package com.example.starter.rights.dto;

import com.example.starter.rights.LicenseMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 创建授权请求；窗口为 UTC 半开区间 [start,end)。
 */
public record CreateLicenseRequest(
        @NotBlank String requestId,
        @NotBlank String assetId,
        @NotBlank String region,
        @NotBlank String platform,
        @NotNull Instant start,
        @NotNull Instant end,
        @NotNull LicenseMode mode) {
}
