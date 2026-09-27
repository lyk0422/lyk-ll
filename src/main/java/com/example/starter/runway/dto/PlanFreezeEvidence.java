package com.example.starter.runway.dto;

import java.time.Instant;
import java.util.List;

/**
 * 结构化冻结/冲突证据：无歧义记录校验时的日历版本、窗口、巡检区间与全部受检航班窗。
 *
 * @param calendarVersion 校验所依据的航班日历版本
 * @param runwayId        跑道编号
 * @param windowStart     例行半开窗口起点（UTC）
 * @param windowEnd       例行半开窗口终点（UTC）
 * @param inspectStart    巡检区间起点（UTC）
 * @param inspectEnd      巡检区间终点（UTC）
 * @param checkedWindows  校验范围内全部同跑道航班占用窗（结构化字段，非分隔符拼接文本）
 * @param conflictingFlightNos 与巡检区间正长度相交的航班号（空列表表示无冲突）
 */
public record PlanFreezeEvidence(
        int calendarVersion,
        String runwayId,
        Instant windowStart,
        Instant windowEnd,
        Instant inspectStart,
        Instant inspectEnd,
        List<FlightWindowView> checkedWindows,
        List<String> conflictingFlightNos) {

    /**
     * 证据中的航班占用窗视图。
     */
    public record FlightWindowView(
            String flightNo,
            Instant occupyStart,
            Instant occupyEnd) {
    }
}
