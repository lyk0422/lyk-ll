package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 例行巡检计划申请发布请求。
 *
 * @param requestId       幂等请求标识
 * @param runwayId        指定跑道（合成数据；合法文本可含分隔符）
 * @param windowStart     UTC 半开窗口起点（含）
 * @param windowEnd       UTC 半开窗口终点（不含）
 * @param durationMinutes 预计巡检分钟数（正整数）：巡检区间 [windowStart, windowStart+duration) 须完整落在窗口内
 * @param calendarVersion 发布所依据并冻结的航班日历版本；须为当前最新版本，否则待重审/拒绝
 */
public record PlanPublishRequest(
        @NotBlank String requestId,
        @NotBlank String runwayId,
        @NotNull Instant windowStart,
        @NotNull Instant windowEnd,
        @Positive int durationMinutes,
        @Positive int calendarVersion) {
}
