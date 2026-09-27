package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;

/**
 * 例行巡检计划发布请求：指定跑道、UTC 半开窗口、预计巡检分钟数与检查项集合，
 * 按当前最新航班日历版本冻结冲突证据。requestId 幂等。
 *
 * @param requestId      幂等请求标识
 * @param runwayId       跑道编号
 * @param windowStart    巡检窗口开始时刻（UTC，半开含端点）
 * @param windowEnd      巡检窗口结束时刻（UTC，半开不含端点）
 * @param durationMinutes 预计巡检时长（分钟，正整数）：须完整落在窗口内
 * @param checkItems     检查项编码集合（非空、不可重复，合法文本允许包含分隔符）
 */
public record PlanPublishRequest(
        @NotBlank String requestId,
        @NotBlank String runwayId,
        @NotNull Instant windowStart,
        @NotNull Instant windowEnd,
        @Positive int durationMinutes,
        @NotEmpty List<@NotBlank String> checkItems) {
}
