package com.example.starter.ventilation.repo;

import java.time.LocalDateTime;

/**
 * event_log 表行记录。
 */
public record EventRow(
        Long id,
        Long modeId,
        String zoneCode,
        String requestId,
        String eventType,
        String detail,
        LocalDateTime eventTime) {
}
