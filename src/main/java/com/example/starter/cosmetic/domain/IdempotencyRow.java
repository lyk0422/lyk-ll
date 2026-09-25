package com.example.starter.cosmetic.domain;

import java.time.Instant;

/**
 * 幂等处理记录行。
 */
public record IdempotencyRow(
        String requestId,
        String endpoint,
        String fingerprint,
        Integer responseStatus,
        String responseBody,
        Instant createdAt) {
}
