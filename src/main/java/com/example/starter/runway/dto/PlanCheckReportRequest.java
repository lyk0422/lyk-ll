package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 例行巡检检查项回报请求；expectedVersion 为计划聚合当前版本，不匹配返回409。requestId 幂等。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 客户端持有的计划版本号
 * @param itemCode        检查项编码（合法文本允许包含分隔符）
 * @param completedAt     检查项完成时刻（UTC，精确时刻参与指纹）
 */
public record PlanCheckReportRequest(
        @NotBlank String requestId,
        @Positive int expectedVersion,
        @NotBlank String itemCode,
        @NotNull Instant completedAt) {
}
