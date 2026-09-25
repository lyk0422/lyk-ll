package com.example.starter.waterlogging.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 预降决策单视图。
 */
public record PlanView(
        Long planId,
        String requestId,
        String basinZone,
        String warningLevel,
        String riverName,
        BigDecimal targetLevel,
        LocalDateTime warningTime,
        BigDecimal gateOpening,
        String status,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime archivedAt,
        List<StationStateView> stations) {

    /**
     * 分区内联动泵站的当前机组与闸门状态。
     */
    public record StationStateView(
            String stationCode,
            String stationName,
            BigDecimal safetyLowLevel,
            String runStatus,
            BigDecimal gateOpening) {
    }
}
