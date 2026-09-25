package com.example.starter.blast.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 监管复核解冻请求体。
 *
 * @param requestId 幂等键
 * @param reviewer  复核人
 * @param confirmed 必须为 true 表示监管复核确认解除冻结
 */
public record UnfreezeReviewRequest(
        @NotBlank String requestId,
        @NotBlank String reviewer,
        @NotNull @AssertTrue Boolean confirmed) {
}
