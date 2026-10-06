package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 根因评估完成请求。
 */
public record CompleteAssessmentRequest(
        @NotBlank String requestId) {
}
