package com.example.starter.runway.dto;

import java.time.Instant;
import java.util.List;

/**
 * 航班日历发布结果：返回新版本号及因本次升版转入待重审的计划。
 *
 * @param version         新发布的日历版本号（从1开始）
 * @param publishedAt     发布时刻（UTC）
 * @param flightCount     本次发布的占用窗数量
 * @param needsReviewPlanIds 冻结旧版本且尚未开始、被置为待重审的存活计划ID
 */
public record CalendarPublishResponse(
        int version,
        Instant publishedAt,
        int flightCount,
        List<Long> needsReviewPlanIds) {
}
