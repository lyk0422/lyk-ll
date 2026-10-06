package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 事件详情响应。时刻均为 UTC。
 */
public record EventResponse(
        long id,
        String frequencyMhz,
        String status,
        boolean escalated,
        Instant escalatedAt,
        Instant createdAt,
        Instant reviewedAt,
        String reviewerId,
        String reviewConclusion,
        Instant closedAt,
        String closedBy) {
}
