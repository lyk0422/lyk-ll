package com.example.starter.runway.dto;

/**
 * 巡检评估响应：记录明细与评估后的事件视图。
 */
public record InspectionResponse(
        InspectionView inspection,
        ClosureEventView event) {
}
