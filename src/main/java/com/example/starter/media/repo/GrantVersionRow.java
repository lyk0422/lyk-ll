package com.example.starter.media.repo;

import com.example.starter.media.domain.GrantStatus;
import com.example.starter.media.domain.LicenseMode;

import java.time.Instant;

/**
 * 授权版本行（内部使用）：携带版本表主键 rowId 与授权线 grantId，供排期绑定。
 */
public record GrantVersionRow(
        long rowId,
        long grantId,
        int versionNo,
        String assetId,
        String region,
        String platform,
        Instant windowStart,
        Instant windowEnd,
        LicenseMode mode,
        GrantStatus status
) {
}
