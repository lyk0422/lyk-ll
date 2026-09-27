package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 例行巡检开始结果。
 *
 * @param planId          计划ID
 * @param status          开始后的计划状态（RUNNING）
 * @param version         开始后的计划聚合版本
 * @param startedAt       巡检开始时刻（UTC）
 * @param calendarVersion 开始时重新确认的当前日历版本
 */
public record PlanStartResponse(
        long planId,
        String status,
        int version,
        Instant startedAt,
        int calendarVersion) {
}
