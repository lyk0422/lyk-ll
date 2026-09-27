package com.example.starter.runway;

import java.time.Instant;

/**
 * 例行巡检计划行：对应 inspection_plan 表。
 *
 * @param id                      计划主键
 * @param runwayId                跑道编号（合成数据）
 * @param windowStart             UTC 半开窗口起点（含）
 * @param windowEnd               UTC 半开窗口终点（不含）
 * @param plannedStartAt          计划巡检开始时刻（UTC）
 * @param durationMinutes         预计巡检分钟数（正整数）
 * @param status                  计划状态
 * @param version                 计划聚合版本：从1开始，每次成功状态/内容变更加1
 * @param frozenCalendarVersion   发布/重审冻结所依据的航班日历版本
 * @param conflictEvidence        结构化冻结/冲突证据（JSON 原文）
 * @param liveFlag                获准占位：1=FROZEN/RUNNING，null=其他
 * @param abortedByEventId        中止该计划的紧急关闭事件ID，仅 ABORTED 非 null
 * @param createdAt               记录创建时间（UTC）
 * @param updatedAt               记录最近更新时间（UTC）
 */
public record InspectionPlanRecord(
        long id,
        String runwayId,
        Instant windowStart,
        Instant windowEnd,
        Instant plannedStartAt,
        int durationMinutes,
        PlanStatus status,
        int version,
        Integer frozenCalendarVersion,
        String conflictEvidence,
        Integer liveFlag,
        Long abortedByEventId,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 计划巡检结束时刻 = 计划开始 + 预计分钟数。
     */
    public Instant plannedEndAt() {
        return plannedStartAt.plusSeconds(durationMinutes * 60L);
    }
}
