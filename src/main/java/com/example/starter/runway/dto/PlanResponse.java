package com.example.starter.runway.dto;

import java.time.Instant;
import java.util.List;

/**
 * 例行巡检计划视图：返回计划状态、聚合版本、冻结证据、已保存检查项与完成回报。
 *
 * @param id                    计划ID
 * @param runwayId              跑道编号
 * @param status                计划状态
 * @param version               计划聚合版本（从1开始，每次成功变更加1）
 * @param windowStart           半开窗口起点（UTC）
 * @param windowEnd             半开窗口终点（UTC）
 * @param plannedStartAt        计划巡检开始时刻（UTC）
 * @param plannedEndAt          计划巡检结束时刻（UTC）
 * @param frozenCalendarVersion 冻结所依据的日历版本
 * @param evidence              结构化冻结/冲突证据
 * @param abortedByEventId      中止该计划的紧急关闭事件ID（仅 ABORTED 非null）
 * @param checks                已保存检查项（中止后原样保留）
 * @param completionCount       完成回报总数（含迟到登记）
 * @param lateCompletionCount   迟到完成登记数（紧急中止后收到，不开放跑道）
 * @param updatedAt             最近更新时间（UTC）
 */
public record PlanResponse(
        long id,
        String runwayId,
        String status,
        int version,
        Instant windowStart,
        Instant windowEnd,
        Instant plannedStartAt,
        Instant plannedEndAt,
        Integer frozenCalendarVersion,
        PlanFreezeEvidence evidence,
        Long abortedByEventId,
        List<PlanCheckItemView> checks,
        int completionCount,
        int lateCompletionCount,
        Instant updatedAt) {
}
