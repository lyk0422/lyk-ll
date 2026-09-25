package com.example.starter.repo;

import java.math.BigDecimal;

// dive_segment 行记录：pairState 为 PENDING/EFFECTIVE/FROZEN；rateMPerMin 仅升降段有值。
public record SegmentRow(
        Long id,
        long operationId,
        int planVersion,
        String requestId,
        String diverId,
        String phase,
        int startDepthM,
        int endDepthM,
        long startedAt,
        long endedAt,
        long durationSeconds,
        BigDecimal rateMPerMin,
        boolean rateAlarm,
        String pairState,
        String pairKey,
        Long pairGroupId,
        boolean frozen,
        long createdAt) {
}
