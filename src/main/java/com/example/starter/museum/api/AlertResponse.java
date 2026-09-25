package com.example.starter.museum.api;

import com.example.starter.museum.domain.AlertStatus;

import java.time.Instant;

/** 环境异常视图。 */
public record AlertResponse(
        String alertNo,
        String showcaseNo,
        String contractNo,
        Instant triggerTime,
        AlertStatus status,
        Instant escalatedAt,
        Instant recoveredAt,
        Instant resolvedAt
) {
}
