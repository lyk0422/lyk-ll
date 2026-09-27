package com.example.starter.wb.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 舱单版本行（manifest_version）。
 */
public record ManifestRow(
        Long id,
        long flightId,
        int versionNo,
        Long parentVersionId,
        String requestId,
        String fingerprint,
        String status,
        int passengerCount,
        BigDecimal payloadKg,
        BigDecimal fuelKg,
        BigDecimal zfwKg,
        BigDecimal towKg,
        BigDecimal cgZfwPct,
        BigDecimal cgTowPct,
        boolean withinLimits,
        int lmcPaxDelta,
        BigDecimal lmcWeightDelta,
        boolean assemblyControlled,
        Long activeFlag,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime closedAt
) {
}
