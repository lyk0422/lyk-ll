package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 展示池分区行记录。
 */
public record ZonePool(
        long id,
        String code,
        String name,
        ZoneStatus status,
        int consecutiveViolations,
        SensorState sensorState,
        LocalDateTime lastHeartbeatAt,
        LocalDateTime missingSince,
        LocalDateTime updatedAt
) {
}
