package com.example.starter.domain;

import java.time.Instant;

/**
 * 监管复核解除冻结记录。
 */
public record FreezeReview(
        long id,
        String requestId,
        String fingerprint,
        long mineId,
        String reviewer,
        Instant reviewedAt) {
}
