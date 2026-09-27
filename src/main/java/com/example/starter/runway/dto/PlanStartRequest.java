package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 例行巡检开始请求。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 计划聚合乐观锁版本（从1开始）；与库内不符返回 409
 * @param startedAt       精确开始时刻（UTC，须落在窗口内）
 */
public record PlanStartRequest(
        @NotBlank String requestId,
        @Positive int expectedVersion,
        @NotNull Instant startedAt) {
}
