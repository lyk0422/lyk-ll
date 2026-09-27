package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 航班日历变更请求：版本加一，尚未开始的例行计划转为待重审。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 期望的当前日历版本
 * @param changedAt       变更时间（UTC）
 */
public record CalendarChangeRequest(
        @NotBlank String requestId,
        @Positive int expectedVersion,
        @NotNull Instant changedAt) {
}
