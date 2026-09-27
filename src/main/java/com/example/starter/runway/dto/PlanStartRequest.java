package com.example.starter.runway.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 例行巡检开始请求：开始须重新确认日历版本（冻结版本须仍为当前版本）且跑道无进行中紧急关闭事件。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 期望的计划聚合版本（乐观并发控制）
 * @param startedAt       巡检开始精确时刻（UTC）
 */
public record PlanStartRequest(
        @NotBlank String requestId,
        @Min(1) int expectedVersion,
        @NotNull Instant startedAt) {
}
