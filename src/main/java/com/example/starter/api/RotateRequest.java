package com.example.starter.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 令牌轮换请求。
 *
 * @param requestId    请求键（客户端生成的幂等键）
 * @param refreshToken 当前刷新令牌明文
 * @param clientId     客户端标识
 */
public record RotateRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 512) String refreshToken,
        @NotBlank @Size(max = 128) String clientId
) {
}
