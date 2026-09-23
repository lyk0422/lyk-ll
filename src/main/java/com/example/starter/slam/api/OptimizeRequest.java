package com.example.starter.slam.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 优化提交请求。
 *
 * @param requestId 客户端幂等键，重复提交返回同一任务
 * @param graphId   目标图 ID
 */
public record OptimizeRequest(
        @NotBlank String requestId,
        @NotBlank String graphId
) {
}
