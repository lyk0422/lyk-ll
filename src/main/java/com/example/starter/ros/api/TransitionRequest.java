package com.example.starter.ros.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 生命周期迁移请求。
 *
 * @param targetState 目标状态，必须是当前状态的合法相邻状态
 * @param expectedVersion 客户端持有的节点版本号；服务端版本不一致时返回版本冲突
 * @param requestId   幂等键
 */
public record TransitionRequest(
        @NotBlank String targetState,
        @NotNull Long expectedVersion,
        @NotBlank String requestId) {
}
