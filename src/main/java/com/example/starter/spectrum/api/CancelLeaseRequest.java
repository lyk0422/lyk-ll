package com.example.starter.spectrum.api;

import jakarta.validation.constraints.NotNull;

/**
 * 取消租约请求：只改变未来有效性并保留历史版本。
 *
 * @param requestId       幂等键（必填）
 * @param leaseId         待取消租约标识
 * @param expectedVersion 调用方期望的当前版本；不符则拒绝
 */
public record CancelLeaseRequest(
        @NotNull String requestId,
        @NotNull String leaseId,
        @NotNull Integer expectedVersion) {
}
