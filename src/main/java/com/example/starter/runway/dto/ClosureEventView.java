package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 关闭事件视图。
 */
public record ClosureEventView(
        long id,
        String runwayId,
        String eventType,
        String status,
        boolean escalated,
        boolean repairRequired,
        boolean repairCleared,
        Instant reportedAt,
        Instant closedAt,
        Instant reopenedAt,
        Instant archivedAt) {
}
