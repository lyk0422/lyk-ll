package com.example.starter.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 计划详情视图：在计划响应基础上展示实际责任区间与最新交班信息（含意见与接班预占）。
 *
 * @param responsibilities 各引航员实际责任段（半开区间），工时按此分段计算
 * @param handover         最新交班记录，无交班为 null
 */
public record PlanDetailResponse(
        Long planId,
        String status,
        Long pilotId,
        List<Long> tugIds,
        LocalDateTime planStart,
        LocalDateTime planEnd,
        LocalDateTime tideStart,
        LocalDateTime tideEnd,
        List<ResponsibilitySegment> responsibilities,
        HandoverResponse handover) {

    /** 单个引航员的实际责任段（半开区间 [start, end)）。 */
    public record ResponsibilitySegment(Long pilotId, LocalDateTime start, LocalDateTime end) {
    }
}
