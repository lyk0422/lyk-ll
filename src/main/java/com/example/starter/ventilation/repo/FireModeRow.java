package com.example.starter.ventilation.repo;

import java.time.LocalDateTime;

/**
 * fire_mode 表行记录。状态时间为 null 表示尚未进入对应阶段。
 */
public record FireModeRow(
        Long id,
        String requestId,
        String zoneCode,
        String smokeFlowDirection,
        String targetCondition,
        String fingerprint,
        String status,
        String conflictDetail,
        LocalDateTime createdAt,
        LocalDateTime resetStartedAt,
        LocalDateTime resetCompletedAt,
        LocalDateTime archivedAt) {
}
