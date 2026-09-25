package com.example.starter.wb.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * LMC 修正日志行（lmc_log）。
 */
public record LmcLogRow(
        Long id,
        long manifestId,
        String requestId,
        int passengerDelta,
        BigDecimal weightDeltaKg,
        BigDecimal fuelDeltaKg,
        int cumulativePax,
        BigDecimal cumulativeWeight,
        boolean withinLmcLimit,
        Long newVersionId,
        LocalDateTime createdAt
) {
}
