package com.example.starter.rights.model;

import com.example.starter.rights.LicenseMode;
import com.example.starter.rights.LicenseStatus;

import java.time.Instant;

/**
 * 授权版本行。同一 {@code licenseId} 的更正会产生新版本号，旧版本保留。
 */
public record LicenseVersion(
        long id,
        String licenseId,
        int versionNo,
        String assetId,
        String region,
        String platform,
        Instant windowStart,
        Instant windowEnd,
        LicenseMode mode,
        LicenseStatus status,
        Instant createdAt,
        Instant revokedAt,
        String requestId) {
}
