package com.example.starter.schedule.web.dto;

import java.time.Instant;

/**
 * 编排条目视图。
 */
public record EntryViewDto(
        long entryId,
        String requestId,
        long satelliteId,
        long windowId,
        int priority,
        String status,
        Instant startTime,
        Instant endTime,
        Long preemptedByEntryId,
        Long suggestionWindowId,
        Instant suggestionStartTime,
        Instant suggestionEndTime) {
}
