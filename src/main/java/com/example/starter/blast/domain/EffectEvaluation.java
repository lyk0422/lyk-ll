package com.example.starter.blast.domain;

import java.time.Instant;

/**
 * 效果评估封档持久化对象。
 */
public record EffectEvaluation(
        Long id,
        Long applicationId,
        String requestId,
        String evaluator,
        String conclusion,
        Instant archivedAt) {
}
