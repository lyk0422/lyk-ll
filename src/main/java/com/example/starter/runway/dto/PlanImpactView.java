package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 日历影响视图：冻结日历版本与当前版本不一致的非终态计划。
 *
 * @param planId          计划主键
 * @param status          计划状态
 * @param calendarVersion 计划冻结的日历版本
 * @param windowStart     例行窗口起点（UTC）
 * @param windowEnd       例行窗口终点（UTC）
 */
public record PlanImpactView(
        long planId,
        String status,
        int calendarVersion,
        Instant windowStart,
        Instant windowEnd) {
}
