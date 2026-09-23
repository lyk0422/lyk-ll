package com.example.starter.slam.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 提交异步优化任务请求。
 */
public record OptimizeRequest(
        @NotBlank String requestId
) {
}
