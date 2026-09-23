package com.example.starter.media.web.dto;

import com.example.starter.media.domain.GrantStatus;
import com.example.starter.media.domain.LicenseMode;

import java.time.Instant;

/**
 * 授权版本视图。
 */
public record GrantView(
        String grantId,
        Integer versionNo,
        String assetId,
        String region,
        String platform,
        Instant windowStart,
        Instant windowEnd,
        LicenseMode mode,
        GrantStatus status,
        String requestId,
        Instant createdAt,
        Instant revokedAt
) {
}
