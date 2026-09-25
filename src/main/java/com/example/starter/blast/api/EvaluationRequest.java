package com.example.starter.blast.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 效果评估封档请求体。
 *
 * @param requestId   幂等键
 * @param applicationId 已完成振动回传的作业 ID
 * @param evaluator   评估人
 * @param conclusion  评估结论
 */
public record EvaluationRequest(
        @NotBlank String requestId,
        @NotNull Long applicationId,
        @NotBlank String evaluator,
        @NotBlank String conclusion) {
}
