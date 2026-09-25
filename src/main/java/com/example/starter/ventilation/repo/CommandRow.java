package com.example.starter.ventilation.repo;

import java.time.LocalDateTime;

/**
 * device_command 表行记录。
 */
public record CommandRow(
        Long id,
        String commandUid,
        Long modeId,
        String zoneCode,
        String deviceCode,
        String deviceType,
        String targetState,
        String phase,
        Integer stepOrder,
        Integer attempt,
        String status,
        String blockedReason,
        LocalDateTime issuedAt,
        LocalDateTime ackAt,
        LocalDateTime failedAt,
        LocalDateTime createdAt) {
}
