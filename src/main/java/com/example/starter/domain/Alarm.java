package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 水质告警行记录。
 */
public record Alarm(
        long id,
        long zoneId,
        String params,
        String detail,
        AlarmStatus status,
        long sampleId,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt
) {
}
