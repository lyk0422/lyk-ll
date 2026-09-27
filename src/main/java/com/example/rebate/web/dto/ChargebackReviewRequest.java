package com.example.rebate.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 冲回复核请求：由不同于提交人的复核人批准或驳回，携带冲回单当前版本。
 *
 * @param expectedVersion 冲回单期望版本（从 1 开始，不匹配返回 409）
 * @param action 复核动作：APPROVE 批准 / REJECT 驳回
 * @param reviewer 复核人（不得与提交人相同）
 */
public record ChargebackReviewRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer expectedVersion,
        @NotBlank String action,
        @NotBlank String reviewer) {
}
