package com.example.starter.media.web.dto;

import com.example.starter.media.domain.ScheduleItemStatus;

import java.time.Instant;

/**
 * 排期时段绑定结果视图，携带被绑定的明确授权版本。
 */
public record ScheduleItemView(
        Integer itemNo,
        String assetId,
        String region,
        String platform,
        Instant slotStart,
        Instant slotEnd,
        Long boundVersionId,
        Integer boundVersionNo,
        ScheduleItemStatus status,
        Instant playedAt
) {
}
