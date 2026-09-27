package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 例行巡检计划视图。
 *
 * @param planId            计划主键
 * @param runwayId          跑道编号
 * @param status            计划状态
 * @param windowStart       例行窗口起点（UTC）
 * @param windowEnd         例行窗口终点（UTC）
 * @param inspectionMinutes 预计巡检时长（分钟）
 * @param calendarVersion   冻结的航班日历版本
 * @param version           乐观锁版本
 * @param startedAt         实际开始时间（UTC），未开始为 null
 * @param completedItems    已完成检查项（JSON数组），未开始为 null
 * @param conflictEvidence  发布时冻结的冲突校验证据（JSON）
 */
public record PlanResponse(
        long planId,
        String runwayId,
        String status,
        Instant windowStart,
        Instant windowEnd,
        int inspectionMinutes,
        int calendarVersion,
        int version,
        Instant startedAt,
        String completedItems,
        String conflictEvidence) {
}
