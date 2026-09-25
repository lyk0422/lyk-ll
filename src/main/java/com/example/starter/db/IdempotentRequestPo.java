package com.example.starter.db;

import java.time.LocalDateTime;

/**
 * 幂等请求台账持久化对象。
 */
public record IdempotentRequestPo(
        String requestId,
        String kind,
        String fingerprint,
        Long siloId,
        int statusCode,
        String response,
        LocalDateTime createdAt
) {
}
