package com.example.starter.waterlogging.po;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预降决策单 pre_drain_plan 映射。
 */
public record PreDrainPlan(
        Long id,
        String requestId,
        String basinZone,
        String warningLevelCode,
        String riverName,
        BigDecimal targetLevel,
        LocalDateTime warningTime,
        BigDecimal gateOpening,
        String status,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime archivedAt,
        String fingerprint,
        LocalDateTime createdAt) {
}
