package com.example.starter.token.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建会话请求；用户与客户端均为合成输入。
 *
 * @param userId   合成用户标识
 * @param clientId 合成客户端标识
 */
public record CreateSessionRequest(
        @NotBlank @Size(max = 128) String userId,
        @NotBlank @Size(max = 128) String clientId) {
}
