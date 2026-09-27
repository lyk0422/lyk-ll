package com.example.starter.runway.dto;

import java.time.Instant;
import java.util.List;

/**
 * 日历发布响应：返回新版本号，以及因本次变更而被置为待重审的计划ID。
 *
 * @param version             新发布的日历版本号
 * @param publishedAt         生效时刻（UTC）
 * @param plansNeedingReview  尚未开始、被本次变更置为待重审的计划ID列表（已开始计划不自动平移）
 */
public record CalendarPublishResponse(
        int version,
        Instant publishedAt,
        List<Long> plansNeedingReview) {
}
