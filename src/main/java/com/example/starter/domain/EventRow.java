package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 干扰事件行。所有时刻为 UTC；未发生的时刻字段为 null。
 */
public record EventRow(
        long id,
        BigDecimal frequencyMhz,
        EventStatus status,
        boolean escalated,
        Instant escalatedAt,
        Instant createdAt,
        Instant reviewedAt,
        String reviewerId,
        String reviewConclusion,
        Instant closedAt,
        String closedBy) {
}
