package com.example.starter.runway.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 待重审计划重新审查请求：按当前日历版本重新做占用冲突校验，通过后重新冻结。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 期望的计划聚合版本
 * @param calendarVersion 重新确认所依据的日历版本（须等于当前日历版本）
 */
public record PlanReviewRequest(
        @NotBlank String requestId,
        @Min(1) int expectedVersion,
        @Min(1) int calendarVersion) {
}
