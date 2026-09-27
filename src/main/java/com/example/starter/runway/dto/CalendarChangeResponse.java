package com.example.starter.runway.dto;

import java.util.List;

/**
 * 航班日历变更响应。
 *
 * @param runwayId        跑道编号
 * @param version         变更后的日历版本
 * @param affectedPlanIds 因本次变更转为待重审的计划ID列表
 */
public record CalendarChangeResponse(
        String runwayId,
        int version,
        List<Long> affectedPlanIds) {
}
