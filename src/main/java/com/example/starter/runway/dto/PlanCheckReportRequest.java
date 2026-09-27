package com.example.starter.runway.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;

/**
 * 例行巡检检查项回报请求。requestId 幂等，expectedVersion 为聚合乐观锁版本。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 计划聚合版本（从1开始）；与库内不符返回 409
 * @param reportedAt      回报精确时刻（UTC）
 * @param items           本次回报的检查项集合（结构化指纹，允许文本含分隔符）
 */
public record PlanCheckReportRequest(
        @NotBlank String requestId,
        @Positive int expectedVersion,
        @NotNull Instant reportedAt,
        @NotNull @Valid List<@Valid CheckItemInput> items) {
}
