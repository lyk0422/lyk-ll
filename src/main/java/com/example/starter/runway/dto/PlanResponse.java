package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 例行计划视图（发布/查询/状态流转统一返回）。
 *
 * @param planId           计划ID
 * @param runwayId         跑道编号
 * @param windowStart      窗口起点（含，UTC）
 * @param windowEnd        窗口终点（不含，UTC）
 * @param durationMinutes  预计巡检分钟数
 * @param calendarVersion  当前冻结的航班日历版本
 * @param version          聚合乐观锁版本（从1开始，成功变更加1）
 * @param status           计划状态
 * @param inspectionEnd    巡检区间终点（windowStart+duration），便于调用方核对落窗
 * @param emergencyEventId 抢占计划的紧急关闭事件ID；null 表示未被抢占
 * @param startedAt        实际开始时刻（UTC），未开始为 null
 * @param completedAt      正常完成时刻（UTC），未正常完成为 null
 * @param preemptedAt      被取消/中止时刻（UTC），未被抢占为 null
 * @param evidence         发布冻结的冲突证据 JSON 文本
 */
public record PlanResponse(
        long planId,
        String runwayId,
        Instant windowStart,
        Instant windowEnd,
        int durationMinutes,
        int calendarVersion,
        int version,
        String status,
        Instant inspectionEnd,
        Long emergencyEventId,
        Instant startedAt,
        Instant completedAt,
        Instant preemptedAt,
        String evidence) {
}
