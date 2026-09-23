package com.example.starter.repo;

import java.time.Instant;

/**
 * 幂等请求记录行模型。
 */
public record IdempotentRequestRow(
        long id,
        String requestId,
        String requestKind,
        String fingerprint,
        String responseJson,
        Instant createdAt) {
}
