package com.example.starter.repo;

import java.time.Instant;

/**
 * 幂等请求留痕行：记录 requestId、动作、业务指纹与首次响应，用于同键重放与同键异参 409 判定。
 */
public record IdempotentRow(
        String requestId,
        String action,
        String fingerprint,
        Long planId,
        Integer statusCode,
        String responseBody,
        Instant createdAt
) {
}
