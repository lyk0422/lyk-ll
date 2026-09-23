package com.example.starter.playout.domain;

import java.time.Instant;

/**
 * 幂等请求指纹行；冻结同键请求的首次结果。
 */
public record FingerprintRow(
        long id,
        String channel,
        String requestKey,
        String action,
        String fingerprint,
        int statusCode,
        String frozenResponse,
        Instant createdAt) {
}
