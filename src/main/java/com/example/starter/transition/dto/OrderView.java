package com.example.starter.transition.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 改品种单视图。
 */
public record OrderView(
        String orderNo,
        String paperMachine,
        String fromGrade,
        String toGrade,
        BigDecimal basisWeightFrom,
        BigDecimal basisWeightTo,
        int ashLevelFrom,
        int ashLevelTo,
        BigDecimal basisWeightDeltaPct,
        Integer ashLevelDelta,
        Boolean directSwitch,
        Boolean wireFlush,
        Integer switchTimeSec,
        BigDecimal machineSpeedMMin,
        BigDecimal stockConsistencyPct,
        BigDecimal transitionLengthM,
        Integer currentBlendVersion,
        int consecutiveOutOfTolerance,
        String status,
        LocalDateTime appliedAt,
        LocalDateTime decidedAt,
        LocalDateTime executedAt,
        LocalDateTime disposedAt,
        LocalDateTime completedAt,
        LocalDateTime archivedAt) {
}
