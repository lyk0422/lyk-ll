package com.example.starter.ros.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 保存检查点请求。
 *
 * @param checkpointId 检查点标识
 * @param requestId    幂等键
 */
public record SaveCheckpointRequest(
        @NotBlank String checkpointId,
        @NotBlank String requestId) {
}
