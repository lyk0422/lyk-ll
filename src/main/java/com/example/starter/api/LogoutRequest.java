package com.example.starter.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登出请求：撤销整个令牌族。
 *
 * @param familyId 令牌族标识
 * @param clientId 客户端标识（需与族绑定的客户端一致）
 */
public record LogoutRequest(
        @NotBlank @Size(max = 128) String familyId,
        @NotBlank @Size(max = 128) String clientId
) {
}
