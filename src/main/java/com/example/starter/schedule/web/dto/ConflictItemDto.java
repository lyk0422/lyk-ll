package com.example.starter.schedule.web.dto;

import java.time.Instant;

/**
 * 冲突条目详情（同优先级先到保留时返回）。
 */
public record ConflictItemDto(
        long entryId,
        String requestId,
        long satelliteId,
        int priority,
        Instant startTime,
        Instant endTime,
        String reason) {
}
