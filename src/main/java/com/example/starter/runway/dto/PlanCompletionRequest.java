package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 例行巡检完成回报请求。正常完成仅关闭计划，不取代恢复开放与通告发布流程。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 计划聚合版本（从1开始）；与库内不符返回 409
 * @param completedAt     巡检完成精确时刻（UTC）
 */
public record PlanCompletionRequest(
        @NotBlank String requestId,
        @Positive int expectedVersion,
        @NotNull Instant completedAt) {
}
