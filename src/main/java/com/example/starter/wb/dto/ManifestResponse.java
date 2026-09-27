package com.example.starter.wb.dto;

import com.example.starter.wb.domain.LoadDetailRow;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 舱单版本对外视图。
 */
public record ManifestResponse(
        Long manifestId,
        Long flightId,
        int versionNo,
        Long parentVersionId,
        String requestId,
        String status,
        int passengerCount,
        BigDecimal payloadKg,
        BigDecimal fuelKg,
        BigDecimal zfwKg,
        BigDecimal towKg,
        BigDecimal cgZfwPct,
        BigDecimal cgTowPct,
        boolean withinLimits,
        List<String> violations,
        int lmcPaxDelta,
        BigDecimal lmcWeightDelta,
        boolean lmcWithinLimit,
        Long supersededByVersionId,
        List<LoadDetailRow> details,
        List<String> confirmedRoles,
        boolean frozen,
        LocalDateTime closedAt,
        List<LateAlert> lateAlerts,
        boolean assemblyControlled,
        Integer assemblyPlanVersion
) {
}
