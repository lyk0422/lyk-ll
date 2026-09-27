package com.example.starter.wb.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 集装器装配图版本行（assembly_plan）。
 * 版本自 1 起，成功变更加 1；重量单位 kg，力矩单位 kg·mm（精确整数）。
 */
public record AssemblyPlanRow(
        Long id,
        long flightId,
        long manifestId,
        int manifestVersionNo,
        int planVersion,
        String requestId,
        String fingerprint,
        String status,
        int totalTareKg,
        int totalNetKg,
        int totalWeightKg,
        BigDecimal cargoMomentKgMm,
        BigDecimal cgZfwPct,
        BigDecimal cgTowPct,
        Long activeFlag,
        LocalDateTime submittedAt,
        String createdBy,
        LocalDateTime createdAt
) {
}
