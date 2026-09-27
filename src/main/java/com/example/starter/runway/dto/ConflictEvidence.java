package com.example.starter.runway.dto;

import java.time.Instant;
import java.util.List;

/**
 * 计划发布冻结的结构化冲突证据：无歧义描述判定输入与结果。
 *
 * @param runwayId          跑道编号
 * @param calendarVersion   冻结所依据的航班日历版本
 * @param windowStart       计划半开窗口起点（含）
 * @param windowEnd         计划半开窗口终点（不含）
 * @param inspectionStart   巡检区间起点（等于 windowStart）
 * @param inspectionEnd     巡检区间终点（windowStart + 预计分钟数）
 * @param conflict          是否存在正长度相交（true=冲突，拒绝发布）
 * @param consideredWindows 参与判定的同跑道占用窗集合
 * @param conflictingWindows 与巡检区间正长度相交的占用窗子集
 */
public record ConflictEvidence(
        String runwayId,
        int calendarVersion,
        Instant windowStart,
        Instant windowEnd,
        Instant inspectionStart,
        Instant inspectionEnd,
        boolean conflict,
        List<OccupancyView> consideredWindows,
        List<OccupancyView> conflictingWindows) {
}
