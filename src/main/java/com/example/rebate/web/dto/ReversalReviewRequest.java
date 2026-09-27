package com.example.rebate.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 冲回复核请求：批准或驳回，由不同于提交人的复核人执行，携带 expectedVersion。
 *
 * @param expectedVersion 期望的冲回单版本（从 1 开始，成功变更加 1）
 * @param reviewedBy 复核人，不得与提交人相同
 * @param approved {@code true} 批准；{@code false} 驳回（此时 rejectReason 必填）
 * @param rejectReason 驳回原因，驳回时必填
 */
public record ReversalReviewRequest(
        @NotBlank String requestId,
        @NotNull @Min(1) Integer expectedVersion,
        @NotBlank String reviewedBy,
        @NotNull Boolean approved,
        String rejectReason) {
}
