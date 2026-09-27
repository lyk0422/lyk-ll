package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 例行完成回报请求。
 * RUNNING 上为正常完成（计划转 COMPLETED，仅关闭计划，不直接开放跑道）；
 * ABORTED 后收到只登记迟到（late=true，状态/版本不变，不开放跑道）。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 期望的计划聚合版本
 * @param completedAt     完成精确时刻（UTC）
 */
public record PlanCompleteRequest(
        @NotBlank String requestId,
        int expectedVersion,
        @NotNull Instant completedAt) {
}
