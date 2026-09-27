package com.example.starter.runway;

import java.time.Instant;

/**
 * 例行跑道巡检计划行：对应 inspection_plan 表。
 *
 * @param id                计划主键
 * @param runwayId          跑道编号（合成数据）
 * @param windowStart       例行窗口起点（UTC，半开窗口，含起点）
 * @param windowEnd         例行窗口终点（UTC，半开窗口，不含终点）
 * @param inspectionMinutes 预计巡检时长（分钟），自窗口起点起须完整落在窗口内
 * @param flightWindows     合成航班占用窗（JSON数组）
 * @param calendarVersion   发布或重审时冻结的航班日历版本
 * @param conflictEvidence  发布时冻结的冲突校验证据（JSON）
 * @param status            计划状态
 * @param version           乐观锁版本：从1开始，每次成功变更加1
 * @param requestId         发布幂等请求标识
 * @param startedAt         实际开始时间（UTC），未开始为 null
 * @param completedItems    已完成检查项（JSON数组），未开始为 null
 * @param createdAt         记录创建时间（UTC）
 * @param updatedAt         记录最近更新时间（UTC）
 */
public record InspectionPlan(
        long id,
        String runwayId,
        Instant windowStart,
        Instant windowEnd,
        int inspectionMinutes,
        String flightWindows,
        int calendarVersion,
        String conflictEvidence,
        PlanStatus status,
        int version,
        String requestId,
        Instant startedAt,
        String completedItems,
        Instant createdAt,
        Instant updatedAt) {
}
