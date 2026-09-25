package com.example.starter.domain;

import java.time.Instant;

/**
 * 效果评估，封档为终态。
 */
public record Evaluation(
        long id,
        String requestId,
        String fingerprint,
        long permitId,
        String conclusion,
        Instant sealedAt) {
}
