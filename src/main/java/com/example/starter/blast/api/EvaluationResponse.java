package com.example.starter.blast.api;

import java.time.Instant;

/**
 * 效果评估封档视图。首次封档返回 201；对终态作业的重复封档幂等返回 200 与既有结论。
 */
public record EvaluationResponse(
        Long id,
        Long applicationId,
        String requestId,
        String evaluator,
        String conclusion,
        Instant archivedAt) {
}
