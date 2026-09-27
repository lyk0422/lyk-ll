package com.example.starter.runway.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 例行巡检计划申请发布请求。
 * 巡检区间 [plannedStartAt, plannedStartAt+durationMinutes) 须完整落在半开窗口 [windowStart,windowEnd) 内，
 * 且不得与冻结日历版本下同跑道航班占用窗正长度相交（端点相接合法）。
 *
 * @param requestId       幂等请求标识
 * @param runwayId        跑道编号（合成数据）
 * @param windowStart     UTC 半开窗口起点（含）
 * @param windowEnd       UTC 半开窗口终点（不含）
 * @param plannedStartAt  计划巡检开始精确时刻（UTC）
 * @param durationMinutes 预计巡检分钟数（正整数）
 * @param expectedVersion 期望的当前航班日历版本（发布冻结所依据版本，必须与当前版本一致）
 */
public record PlanPublishRequest(
        @NotBlank String requestId,
        @NotBlank String runwayId,
        @NotNull Instant windowStart,
        @NotNull Instant windowEnd,
        @NotNull Instant plannedStartAt,
        @Positive int durationMinutes,
        @Min(1) int expectedVersion) {
}
