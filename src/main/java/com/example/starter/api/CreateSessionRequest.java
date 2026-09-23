package com.example.starter.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建会话请求：携带合成用户与客户端标识。
 *
 * @param userId   合成用户标识
 * @param clientId 客户端标识
 */
public record CreateSessionRequest(
        @NotBlank @Size(max = 128) String userId,
        @NotBlank @Size(max = 128) String clientId
) {
}
