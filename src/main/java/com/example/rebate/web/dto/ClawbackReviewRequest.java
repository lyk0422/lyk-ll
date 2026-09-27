package com.example.rebate.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 冲回复核请求。批准在同一事务占用剩余可冲额并产生追缴明细；驳回不占额。
 *
 * @param expectedVersion 冲回单当前期望版本（从 1 开始），不匹配返回 409
 * @param approved {@code true} 批准；{@code false} 驳回
 * @param reviewer 复核人，必须不同于提交人
 */
public record ClawbackReviewRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer expectedVersion,
        @NotNull Boolean approved,
        @NotBlank String reviewer) {
}
