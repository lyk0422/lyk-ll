package com.example.starter.token.api;

import java.time.Instant;

/**
 * 会话创建/轮换成功响应。
 *
 * @param familyId     令牌族 ID
 * @param refreshToken 明文刷新令牌（仅此一次返回，数据库不落明文）
 * @param expiresAt    到期时间（UTC）
 * @param replayed     true 表示本次为同请求键的安全重试，返回的是首次派生的子令牌
 */
public record TokenIssuedResponse(
        String familyId,
        String refreshToken,
        Instant expiresAt,
        boolean replayed) {
}
