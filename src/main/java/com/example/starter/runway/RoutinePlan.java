package com.example.starter.runway;

import java.time.Instant;

/**
 * 例行巡检计划行：对应 routine_plan 表。
 *
 * @param id                      计划主键
 * @param runwayId                跑道编号
 * @param windowStart             巡检窗口开始时刻（UTC，半开含端点）
 * @param windowEnd               巡检窗口结束时刻（UTC，半开不含端点）
 * @param durationMinutes         预计巡检时长（分钟）
 * @param frozenCalendarVersion   冻结的航班日历版本号
 * @param status                  计划状态
 * @param activeFlag              存活标记：1=存活，null=终态
 * @param conflictEvidence        冻结时结构化冲突证据（JSON 文本）
 * @param version                 乐观版本号，从1开始
 * @param requestId               发布幂等请求标识
 * @param startedAt               实际开始时刻（UTC），未开始为 null
 * @param completedAt             正常完成时刻（UTC），未完成为 null
 * @param preemptedAt             紧急抢占时刻（UTC），未被抢占为 null
 * @param createdAt               创建时间（UTC）
 * @param updatedAt               更新时间（UTC）
 */
public record RoutinePlan(
        long id,
        String runwayId,
        Instant windowStart,
        Instant windowEnd,
        int durationMinutes,
        int frozenCalendarVersion,
        RoutinePlanStatus status,
        Integer activeFlag,
        String conflictEvidence,
        int version,
        String requestId,
        Instant startedAt,
        Instant completedAt,
        Instant preemptedAt,
        Instant createdAt,
        Instant updatedAt) {
}
