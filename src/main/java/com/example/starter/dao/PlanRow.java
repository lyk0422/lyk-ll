package com.example.starter.dao;

import java.time.Instant;

/**
 * 减压方案版本行。
 */
public record PlanRow(
        Long id,
        String diveRequestId,
        int version,
        int depthM,
        int bottomMin,
        int ndlLimitMin,
        int descentRateLimit,
        int ascentRateLimit,
        String profileJson,
        boolean frozen,
        String fingerprint,
        Instant createdAtUtc) {
}
