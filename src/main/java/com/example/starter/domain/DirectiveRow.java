package com.example.starter.domain;

import java.time.Instant;

/**
 * 限功率指令行。所有时刻为 UTC；未发生的时刻字段为 null。
 */
public record DirectiveRow(
        long id,
        long eventId,
        long transmitterId,
        int limitWatts,
        DirectiveStatus status,
        Integer actualPowerWatts,
        String confirmedBy,
        Instant confirmedAt,
        Instant createdAt,
        Instant supersededAt,
        Instant releasedAt) {
}
