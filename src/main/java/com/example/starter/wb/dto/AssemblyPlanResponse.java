package com.example.starter.wb.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 集装器装配图版本对外视图。
 *
 * @param manifestVersionNo 提交时匹配的舱单版本号
 * @param planVersion       装配图版本，自 1 起
 * @param totalWeightKg     总货重（含皮重），kg；须等于舱单货运业载
 * @param cargoMomentKgMm   货运力矩=Σ(总重×力臂)，kg·mm
 * @param status            ACTIVE/SUPERSEDED/RELEASED
 */
public record AssemblyPlanResponse(
        Long planId,
        Long flightId,
        Long manifestId,
        int manifestVersionNo,
        int planVersion,
        String requestId,
        String status,
        int totalTareKg,
        int totalNetKg,
        int totalWeightKg,
        BigDecimal cargoMomentKgMm,
        BigDecimal cgZfwPct,
        BigDecimal cgTowPct,
        List<AssemblyItemView> items,
        String createdBy,
        LocalDateTime submittedAt,
        LocalDateTime createdAt
) {
}
