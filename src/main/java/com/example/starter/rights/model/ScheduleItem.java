package com.example.starter.rights.model;

import com.example.starter.rights.ScheduleItemStatus;

import java.time.Instant;

/**
 * 排期时段行：提交时绑定明确的授权版本，状态后续可能转为 BLOCKED。
 */
public record ScheduleItem(
        long id,
        String submissionId,
        int itemIndex,
        String assetId,
        String region,
        String platform,
        Instant segmentStart,
        Instant segmentEnd,
        ScheduleItemStatus status,
        String licenseId,
        int licenseVersion,
        Instant boundAt,
        Instant blockedAt) {
}
