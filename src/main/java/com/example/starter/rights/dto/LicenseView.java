package com.example.starter.rights.dto;

import com.example.starter.rights.LicenseMode;
import com.example.starter.rights.LicenseStatus;

import java.time.Instant;

/**
 * 授权版本视图。
 */
public record LicenseView(
        String licenseId,
        int version,
        String assetId,
        String region,
        String platform,
        Instant start,
        Instant end,
        LicenseMode mode,
        LicenseStatus status,
        Instant createdAt,
        Instant revokedAt) {
}
