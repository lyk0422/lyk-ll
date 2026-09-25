package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 掺烧方案主实体。
 */
public record CoFiringPlan(
        Long id,
        String requestId,
        String planNo,
        String kilnId,
        Long licenseId,
        BigDecimal substitutionRate,
        PlanStatus status,
        LocalDateTime plannedStart,
        LocalDateTime plannedEnd,
        String rejectReason,
        String rejectCategory,
        LocalDateTime submittedAt,
        LocalDateTime reviewedAt,
        LocalDateTime startedAt,
        LocalDateTime stoppedAt,
        LocalDateTime bannedAt,
        LocalDateTime sealedAt,
        java.time.LocalDate sealedDate) {
}
