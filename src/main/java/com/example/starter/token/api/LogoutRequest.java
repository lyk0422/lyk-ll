package com.example.starter.token.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登出请求；撤销整个令牌族。
 *
 * @param refreshToken 族内任意明文刷新令牌
 * @param clientId     合成客户端标识
 */
public record LogoutRequest(
        @NotBlank @Size(max = 1024) String refreshToken,
        @NotBlank @Size(max = 128) String clientId) {
}
