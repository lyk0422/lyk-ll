package com.example.starter.orchestrator.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 取消请求体。requestId 用于取消操作的幂等识别（同一 requestId 重复取消结果一致）。
 */
public record CancelPlanRequest(
        @NotBlank String requestId
) {
}
