package com.example.starter.wb.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 集装器装配图版本行（cargo_loading_plan）。
 * 一次提交写一张新图；cargoGrossKg 为含皮重总货重，totalMomentKgMm 为
 * Σ 含皮重总重 × 整数毫米力臂 的精确求和（kg·mm）。
 * planVersion 在航班范围内自 1 起单调递增（LMC 重打后继续累加）。
 * fuelKg/lmcPaxDelta/lmcWeightDelta 为提交时舱单状态快照：LMC 或加油变更后与当前舱单
 * 不一致时，图仍可追溯但复核必须对应最新舱单（须重新提交图）。
 */
public record CargoPlanRow(
        Long id,
        long flightId,
        long manifestId,
        int manifestVersionNo,
        int planVersion,
        String requestId,
        String fingerprint,
        int cargoNetKg,
        int cargoGrossKg,
        long totalMomentKgMm,
        BigDecimal fuelKg,
        int lmcPaxDelta,
        BigDecimal lmcWeightDelta,
        BigDecimal cgZfwPct,
        BigDecimal cgTowPct,
        boolean withinLimits,
        String createdBy,
        LocalDateTime occurredAt,
        LocalDateTime createdAt
) {
}
