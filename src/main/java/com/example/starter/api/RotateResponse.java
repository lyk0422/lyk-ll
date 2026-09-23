package com.example.starter.api;

import java.time.Instant;

/**
 * 令牌轮换响应。
 *
 * @param familyId     令牌族标识
 * @param refreshToken 新的子刷新令牌明文；安全重试命中首次结果时返回首次子令牌
 * @param expiresAt    子令牌到期时间（UTC）
 * @param requestId    请求键
 * @param replayed     是否为安全重试（同键同参重放）返回的首次结果
 */
public record RotateResponse(
        String familyId,
        String refreshToken,
        Instant expiresAt,
        String requestId,
        boolean replayed
) {
}
