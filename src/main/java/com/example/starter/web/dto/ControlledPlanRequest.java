package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/**
 * 受控减压方案生成/变更请求（虚构积分模型）。
 *
 * @param requestId        本次出方案操作的幂等键
 * @param diveRequestId    所属受控潜次 requestId
 * @param expectedVersion  期望的当前方案版本；首次出方案须为 0，成功后版本 +1（版本从 1 开始）
 * @param depthM           方案深度，米
 * @param bottomMin        方案水下分钟（整数），须不超过两人较短允许时长，恰用尽合法
 * @param plannedDescentAt 精确计划下潜时刻（UTC），积分消减与间隔门禁按此精确时刻复算
 */
public record ControlledPlanRequest(
        @NotBlank String requestId,
        @NotBlank String diveRequestId,
        @NotNull @PositiveOrZero Integer expectedVersion,
        @Positive int depthM,
        @Positive int bottomMin,
        @NotNull Instant plannedDescentAt) {
}
