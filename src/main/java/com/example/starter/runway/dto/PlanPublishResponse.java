package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 例行计划发布成功结果：状态 APPROVED，计划版本1，冻结最新日历版本。
 *
 * @param id                    新计划ID
 * @param runwayId              跑道编号
 * @param status                计划状态（APPROVED）
 * @param version               计划版本号（从1开始）
 * @param frozenCalendarVersion 冻结的日历版本号
 * @param windowStart           窗口开始（UTC）
 * @param windowEnd             窗口结束（UTC）
 * @param inspectionStart       巡检区间起（UTC，等于窗口开始）
 * @param inspectionEnd         巡检区间止（UTC，起+预计时长）
 */
public record PlanPublishResponse(
        long id,
        String runwayId,
        String status,
        int version,
        int frozenCalendarVersion,
        Instant windowStart,
        Instant windowEnd,
        Instant inspectionStart,
        Instant inspectionEnd) {
}
