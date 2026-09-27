package com.example.starter.runway.dto;

import java.time.Instant;
import java.util.List;

/**
 * 例行计划详情视图：含冻结证据解析结果与检查项状态。
 *
 * @param id                    计划ID
 * @param runwayId              跑道编号
 * @param windowStart           窗口开始（UTC，半开含端点）
 * @param windowEnd             窗口结束（UTC，半开不含端点）
 * @param durationMinutes       预计巡检分钟数
 * @param inspectionStart       巡检区间起（UTC）：默认等于窗口开始
 * @param inspectionEnd         巡检区间止（UTC）：起+预计时长，须不晚于窗口结束
 * @param frozenCalendarVersion 冻结的日历版本号
 * @param latestCalendarVersion 当前最新日历版本号（无日历为0）
 * @param status                计划状态
 * @param version               计划版本号（从1开始）
 * @param startedAt             实际开始时刻（UTC）
 * @param completedAt           正常完成时刻（UTC）
 * @param preemptedAt           紧急抢占时刻（UTC）
 * @param checkedFlights        冻结时受检的全部航班窗证据
 * @param conflicts             冻结证据中的冲突行（获准计划恒为空）
 * @param checkItems            检查项视图列表
 */
public record RoutinePlanView(
        long id,
        String runwayId,
        Instant windowStart,
        Instant windowEnd,
        int durationMinutes,
        Instant inspectionStart,
        Instant inspectionEnd,
        int frozenCalendarVersion,
        int latestCalendarVersion,
        String status,
        int version,
        Instant startedAt,
        Instant completedAt,
        Instant preemptedAt,
        List<FrozenFlightView> checkedFlights,
        List<PlanConflictView> conflicts,
        List<CheckItemView> checkItems) {

    /**
     * 冻结证据中的单个受检航班窗。
     */
    public record FrozenFlightView(
            String flightNo,
            Instant occupancyStart,
            Instant occupancyEnd) {
    }
}
