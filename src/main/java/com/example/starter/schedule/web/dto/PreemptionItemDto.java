package com.example.starter.schedule.web.dto;

import java.time.Instant;

/**
 * 抢占结果：被挤占条目及系统生成的重排建议。
 */
public record PreemptionItemDto(
        long preemptedEntryId,
        String requestId,
        long satelliteId,
        int priority,
        Long suggestionWindowId,
        Instant suggestionStartTime,
        Instant suggestionEndTime) {
}
