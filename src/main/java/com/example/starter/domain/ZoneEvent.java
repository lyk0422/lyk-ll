package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 展示池事件流水行记录。
 */
public record ZoneEvent(
        long id,
        long zoneId,
        EventType eventType,
        String fromStatus,
        String toStatus,
        String detail,
        LocalDateTime occurredAt
) {
}
