package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 结构化冲突证据视图：与巡检区间正长度相交的单个航班占用窗。
 *
 * @param flightNo       合成航班号
 * @param occupancyStart 占用开始时刻（UTC，含）
 * @param occupancyEnd   占用结束时刻（UTC，不含）
 * @param overlapMinutes 相交长度（分钟，正整数；端点相接不产生证据行）
 */
public record PlanConflictView(
        String flightNo,
        Instant occupancyStart,
        Instant occupancyEnd,
        long overlapMinutes) {
}
