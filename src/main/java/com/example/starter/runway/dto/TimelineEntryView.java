package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 状态时间线条目视图。
 */
public record TimelineEntryView(
        String fromStatus,
        String toStatus,
        String reason,
        Instant occurredAt) {
}
