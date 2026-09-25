package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 调度单视图。
 */
public record OrderResponse(
        long orderId,
        String requestId,
        String stationCode,
        long eventId,
        String genCode,
        String status,
        Instant arrivalDeadlineUtc,
        Instant dispatchDeadline,
        Instant confirmedAt,
        Instant abnormalAt,
        Instant releasedAt,
        Instant closedAt,
        Instant createdAt
) {
}
