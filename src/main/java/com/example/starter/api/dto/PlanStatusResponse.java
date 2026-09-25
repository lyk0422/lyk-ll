package com.example.starter.api.dto;

import java.time.LocalDateTime;

/**
 * 方案状态视图。
 */
public record PlanStatusResponse(
        Long planId,
        String planNo,
        String kilnId,
        String status,
        java.math.BigDecimal substitutionRate,
        LocalDateTime submittedAt,
        LocalDateTime reviewedAt,
        LocalDateTime startedAt,
        LocalDateTime stoppedAt,
        LocalDateTime bannedAt,
        LocalDateTime sealedAt) {
}
