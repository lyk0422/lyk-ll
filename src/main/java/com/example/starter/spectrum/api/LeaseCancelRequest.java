package com.example.starter.spectrum.api;

import jakarta.validation.constraints.NotNull;

/**
 * 取消租约请求。
 *
 * @param requestId       客户端请求幂等键（必传）
 * @param expectedVersion 期望租约当前版本；用于并发取消协调
 */
public record LeaseCancelRequest(@NotNull String requestId,
                                 Integer expectedVersion) {
}
