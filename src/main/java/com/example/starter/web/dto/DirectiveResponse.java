package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 限功率指令响应（轨迹可复算）。时刻均为 UTC。
 */
public record DirectiveResponse(
        long id,
        long eventId,
        long transmitterId,
        int limitWatts,
        String status,
        Integer actualPowerWatts,
        String confirmedBy,
        Instant confirmedAt,
        Instant createdAt,
        Instant supersededAt,
        Instant releasedAt) {
}
