package com.example.starter.ros.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 从检查点恢复请求（当前 JVM 内恢复）。
 *
 * @param checkpointId 检查点标识
 * @param requestId    幂等键
 */
public record RestoreCheckpointRequest(
        @NotBlank String checkpointId,
        @NotBlank String requestId) {
}
