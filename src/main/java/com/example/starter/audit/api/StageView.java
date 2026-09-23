package com.example.starter.audit.api;

import java.time.Instant;
import java.util.Map;

/**
 * 阶段执行记录视图。
 */
public record StageView(
        String stage,
        int attempt,
        String state,
        Instant startedAt,
        Instant finishedAt,
        Map<String, Double> metrics,
        String errorMessage,
        String artifactPath,
        String artifactSha256,
        String outputDigest,
        String replayCommand
) {
}
