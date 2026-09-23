package com.example.starter.token.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 轮换请求。
 *
 * @param requestId    请求键：同一请求键的安全重试必须返回首次派生出的子令牌
 * @param refreshToken 当前明文刷新令牌
 * @param clientId     合成客户端标识
 */
public record RotateRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 1024) String refreshToken,
        @NotBlank @Size(max = 128) String clientId) {
}
