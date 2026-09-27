package com.example.starter.runway;

import java.time.Instant;

/**
 * 例行跑道巡检计划行：对应 routine_plan 表。
 *
 * @param id                计划主键
 * @param runwayId          跑道编号（合成数据）
 * @param windowStart       UTC 半开窗口起点（含）
 * @param windowEnd         UTC 半开窗口终点（不含）
 * @param durationMinutes   预计巡检时长（分钟，正整数）
 * @param calendarVersion   发布时冻结的航班日历版本
 * @param version           聚合乐观锁版本：从1开始，每次成功变更加1；写操作携带 expectedVersion
 * @param status            当前状态
 * @param evidence          发布冻结的冲突证据 JSON
 * @param slotFlag          未终态获准占用标记（1=占用，终态 null）
 * @param emergencyEventId  抢占本计划的紧急关闭事件ID；null 表示未被抢占
 * @param startedAt         实际开始时刻（UTC），未开始为 null
 * @param completedAt       正常完成时刻（UTC），未正常完成为 null
 * @param preemptedAt       被紧急取消/中止时刻（UTC），未被抢占为 null
 * @param requestId         计划发布幂等请求标识
 * @param createdAt         记录创建时间（UTC）
 * @param updatedAt         记录最近更新时间（UTC）
 */
public record RoutinePlan(
        long id,
        String runwayId,
        Instant windowStart,
        Instant windowEnd,
        int durationMinutes,
        int calendarVersion,
        int version,
        RoutinePlanStatus status,
        String evidence,
        Integer slotFlag,
        Long emergencyEventId,
        Instant startedAt,
        Instant completedAt,
        Instant preemptedAt,
        String requestId,
        Instant createdAt,
        Instant updatedAt) {
}
