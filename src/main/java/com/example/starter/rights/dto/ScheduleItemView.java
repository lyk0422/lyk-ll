package com.example.starter.rights.dto;

import com.example.starter.rights.ScheduleItemStatus;

import java.time.Instant;

/**
 * 排期时段视图：携带绑定的授权版本与当前状态。
 */
public record ScheduleItemView(
        int index,
        String assetId,
        String region,
        String platform,
        Instant start,
        Instant end,
        ScheduleItemStatus status,
        String licenseId,
        int licenseVersion,
        Instant boundAt,
        Instant blockedAt) {
}
