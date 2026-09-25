package com.example.starter.deicing.api;

import jakarta.validation.constraints.NotNull;

/**
 * 取消请求体。
 *
 * @param requestId 申请幂等键
 * @param reason    取消原因，可空
 */
public record CancelRequest(
        @NotNull String requestId,
        String reason
) {
}
