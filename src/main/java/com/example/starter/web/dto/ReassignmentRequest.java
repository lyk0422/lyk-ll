package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发电确认超时后的改派请求。newGenCode 省略时自动选择一台空闲油机。
 */
public record ReassignmentRequest(
        @NotBlank String requestId,
        @NotBlank String orderRequestId,
        String newGenCode
) {
}
