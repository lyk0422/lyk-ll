package com.example.starter.api;

import java.time.Instant;

/**
 * 创建会话响应。
 *
 * @param familyId     令牌族标识
 * @param refreshToken 首个刷新令牌明文（仅此一次返回，数据库不保存明文）
 * @param expiresAt    令牌到期时间（UTC）
 * @param createdAt    令牌创建时间（UTC）
 */
public record CreateSessionResponse(
        String familyId,
        String refreshToken,
        Instant expiresAt,
        Instant createdAt
) {
}
