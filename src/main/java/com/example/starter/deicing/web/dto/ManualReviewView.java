package com.example.starter.deicing.web.dto;

import java.time.Instant;

/**
 * 人工确认队列条目视图。
 */
public record ManualReviewView(
        String requestId,
        String flightNumber,
        String reason,
        String detail,
        String status,
        Instant createdAt) {
}
