package com.example.starter.wb.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 装配图对外视图。
 *
 * @param manifestVersionMatch 图所挂舱单版本是否仍为当前有效舱单（LMC 重打后为 false）
 */
public record CargoPlanResponse(
        Long manifestId,
        Long flightId,
        int manifestVersionNo,
        int planVersion,
        String requestId,
        String manifestStatus,
        int cargoNetKg,
        int cargoGrossKg,
        long totalMomentKgMm,
        BigDecimal cgZfwPct,
        BigDecimal cgTowPct,
        boolean withinLimits,
        boolean manifestVersionMatch,
        List<UldItemView> items,
        List<String> confirmedRoles,
        LocalDateTime occurredAt,
        LocalDateTime createdAt
) {
}
