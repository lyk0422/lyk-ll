package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/**
 * 日历影响查询请求（GET 查询参数）：检查给定窗口按指定日历版本（缺省为最新版本）的冲突。
 *
 * @param runwayId        跑道编号
 * @param windowStart     窗口开始（UTC，半开含端点）
 * @param windowEnd       窗口结束（UTC，半开不含端点）
 * @param durationMinutes 预计巡检分钟数（正整数）
 * @param calendarVersion 指定日历版本；null 表示取最新版本
 */
public record CalendarImpactQuery(
        @NotBlank String runwayId,
        @NotNull Instant windowStart,
        @NotNull Instant windowEnd,
        @Positive int durationMinutes,
        @PositiveOrZero Integer calendarVersion) {
}
