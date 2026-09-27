package com.example.starter.runway.dto;

import java.time.Instant;
import java.util.List;

/**
 * 日历影响查询结果：指定版本（默认最新）下，与给定巡检区间正长度相交的合成航班占用窗。
 *
 * @param calendarVersion 查询所依据的日历版本号
 * @param runwayId        跑道编号
 * @param windowStart     窗口开始（UTC）
 * @param windowEnd       窗口结束（UTC）
 * @param inspectionStart 巡检区间起（UTC）
 * @param inspectionEnd   巡检区间止（UTC）
 * @param conflicts       正长度相交的航班窗证据；端点相接不出现
 */
public record CalendarImpactResponse(
        int calendarVersion,
        String runwayId,
        Instant windowStart,
        Instant windowEnd,
        Instant inspectionStart,
        Instant inspectionEnd,
        List<PlanConflictView> conflicts) {
}
