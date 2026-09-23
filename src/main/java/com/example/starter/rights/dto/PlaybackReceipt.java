package com.example.starter.rights.dto;

import com.example.starter.rights.LicenseMode;

import java.time.Instant;

/**
 * 播出完成回执：携带播出时间与排期版本（授权证据快照）。
 */
public record PlaybackReceipt(
        String submissionId,
        int index,
        Instant airStart,
        Instant airEnd,
        String licenseId,
        int licenseVersion,
        Instant windowStart,
        Instant windowEnd,
        LicenseMode mode,
        Instant completedAt) {
}
