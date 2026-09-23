package com.example.starter.media.web.dto;

import com.example.starter.media.domain.ScheduleItemStatus;

import java.time.Instant;

/**
 * 播出完成回执：携带播出时间、排期版本与被绑定的明确授权版本。
 */
public record ReceiptView(
        String scheduleId,
        Integer scheduleVersionNo,
        Integer itemNo,
        Instant slotStart,
        Instant slotEnd,
        Instant playedAt,
        Long boundVersionId,
        Integer boundVersionNo,
        ScheduleItemStatus status
) {
}
