package com.example.starter.ros.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 生命周期状态迁移请求。
 *
 * @param expectedVersion 调用方持有的节点版本,与当前版本不一致时返回 409(版本冲突)
 */
public record TransitionRequest(
        @NotBlank String nodeId,
        @NotBlank String targetState,
        @NotNull Long expectedVersion,
        @NotBlank String requestId
) {
}
