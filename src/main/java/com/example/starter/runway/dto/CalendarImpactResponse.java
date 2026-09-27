package com.example.starter.runway.dto;

import java.util.List;

/**
 * 日历影响查询结果：给定日历版本下，受影响（冻结版本落后、尚未开始）的计划。
 *
 * @param calendarVersion 查询所依据的当前日历版本
 * @param publishedAt     该版本发布时刻（UTC）
 * @param impactedPlans   受影响计划列表（已开始 RUNNING 与终态计划不在内）
 */
public record CalendarImpactResponse(
        int calendarVersion,
        java.time.Instant publishedAt,
        List<CalendarImpactItem> impactedPlans) {
}
