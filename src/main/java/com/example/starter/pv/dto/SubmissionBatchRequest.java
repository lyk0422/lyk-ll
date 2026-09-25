package com.example.starter.pv.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 监管递交批次创建请求，按 requestId 幂等。
 */
public record SubmissionBatchRequest(
        @NotBlank @Size(max = 64) String requestId) {
}
