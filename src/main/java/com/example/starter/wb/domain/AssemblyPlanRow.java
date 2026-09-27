package com.example.starter.wb.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 集装器装配图版本行（assembly_plan）。
 * 每个航班一张当前有效图（active_flag=flight_id），重新提交作废旧图（active_flag=NULL，历史只读）。
 *
 * @param cargoPayloadKg  装配图总货重=Σ净重，kg，须等于所挂舱单版本货运业载
 * @param cargoMomentKgmm 货运力矩=Σ总重(皮+净)kg×整数毫米力臂，kg·mm
 */
public record AssemblyPlanRow(
        Long id,
        long flightId,
        long manifestId,
        int planVersion,
        int expectedVersion,
        int manifestVersion,
        String requestId,
        String fingerprint,
        BigDecimal cargoPayloadKg,
        BigDecimal fuelKg,
        long cargoMomentKgmm,
        BigDecimal cgZfwPct,
        BigDecimal cgTowPct,
        Long activeFlag,
        String createdBy,
        LocalDateTime occurredAt,
        LocalDateTime createdAt,
        LocalDateTime releasedAt
) {
}
