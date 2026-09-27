package com.example.starter.runway.dto;

import java.time.Instant;
import java.util.List;

/**
 * 日历影响查询响应：给定日历版本下列出占用窗及受其影响的例行计划。
 *
 * @param version       查询的日历版本
 * @param occupancies   该版本全部合成占用窗
 * @param impactedPlans 依据该版本判定受影响（待重审/被冻结证据涉及）的计划视图
 */
public record CalendarImpactResponse(
        int version,
        Instant publishedAt,
        List<OccupancyView> occupancies,
        List<PlanResponse> impactedPlans) {
}
