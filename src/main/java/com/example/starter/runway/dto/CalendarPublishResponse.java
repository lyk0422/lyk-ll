package com.example.starter.runway.dto;

import java.time.Instant;
import java.util.List;

/**
 * 航班日历发布结果：返回新版本号、冻结的占用窗数量与发布时刻。
 *
 * @param version      发布后的日历版本（首次为1，每次成功加1）
 * @param publishedAt  发布精确时刻（UTC）
 * @param windowCount  本次冻结的航班占用窗数量
 * @param impactedPlanIds 因本次版本变更被置为待重审的尚未开始（FROZEN）计划ID
 */
public record CalendarPublishResponse(
        int version,
        Instant publishedAt,
        int windowCount,
        List<Long> impactedPlanIds) {
}
