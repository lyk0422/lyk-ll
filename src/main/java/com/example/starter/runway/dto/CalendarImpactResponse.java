package com.example.starter.runway.dto;

import java.util.List;

/**
 * 日历影响查询响应。
 *
 * @param runwayId       跑道编号
 * @param currentVersion 当前日历版本；该跑道尚无日历记录时为 null
 * @param affectedPlans  受日历版本影响的非终态计划列表
 */
public record CalendarImpactResponse(
        String runwayId,
        Integer currentVersion,
        List<PlanImpactView> affectedPlans) {
}
