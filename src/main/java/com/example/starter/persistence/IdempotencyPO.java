package com.example.starter.persistence;

import java.time.Instant;

/**
 * 通用幂等记录。
 */
public record IdempotencyPO(
        String requestId,
        String operation,
        String fingerprint,
        int httpStatus,
        String resultBody,
        Instant createdAt) {
}
