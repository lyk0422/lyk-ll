package com.example.starter.berth;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 靠泊窗口响应视图。
 */
public record BerthWindowResponse(
        long id,
        String requestId,
        String vesselName,
        long berthId,
        Instant startTime,
        Instant endTime,
        BigDecimal draft,
        int pilotsRequired,
        BerthWindowStatus status,
        List<Long> pilotIds,
        Instant createdAt,
        Instant updatedAt) {
}
