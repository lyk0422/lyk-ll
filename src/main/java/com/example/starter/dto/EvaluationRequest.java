package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 效果评估提交请求（提交即封档）。
 */
public record EvaluationRequest(
        @NotBlank String requestId,
        @NotBlank String conclusion) {
}
