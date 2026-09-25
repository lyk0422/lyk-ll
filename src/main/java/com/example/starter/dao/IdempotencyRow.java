package com.example.starter.dao;

import java.time.Instant;

/**
 * 幂等台账行：requestId 为唯一键。
 */
public record IdempotencyRow(
        String requestId,
        String operation,
        String fingerprint,
        int statusCode,
        String responseJson,
        Instant createdAtUtc) {
}
