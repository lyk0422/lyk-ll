package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 待重审计划的重新审核请求：按指定日历版本（须为最新版本）重新校验冲突，
 * 通过则恢复 APPROVED 并冻结新版本；仍冲突返回422。requestId 幂等。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 客户端持有的计划版本号，不匹配返回409
 * @param calendarVersion 重审所依据的日历版本号（须等于当前最新版本）
 */
public record PlanReviewRequest(
        @NotBlank String requestId,
        @Positive int expectedVersion,
        @Positive int calendarVersion) {
}
