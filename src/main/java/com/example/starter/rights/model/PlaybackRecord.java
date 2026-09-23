package com.example.starter.rights.model;

import com.example.starter.rights.LicenseMode;

import java.time.Instant;

/**
 * 播出完成回执行：授权证据为不可变快照。
 */
public record PlaybackRecord(
        long id,
        String submissionId,
        int itemIndex,
        Instant airStart,
        Instant airEnd,
        String licenseId,
        int licenseVersion,
        Instant windowStart,
        Instant windowEnd,
        LicenseMode mode,
        Instant completedAt,
        String requestId) {
}
