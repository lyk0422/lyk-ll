package com.example.starter.audit.persistence;

import java.time.Instant;

/**
 * task_stage 表行记录。
 * state 阶段状态；metrics_json 为关键指标 JSON（空值表示尚未产出）；
 * artifact_path 产物相对路径；artifact_sha256 产物 SHA-256；
 * output_digest 为阶段输出摘要，供下游与检查点核对；时间均为 UTC。
 */
public record StageRow(
        Long id,
        long taskId,
        String stage,
        int attempt,
        String state,
        Instant startedAt,
        Instant finishedAt,
        String metricsJson,
        String errorMessage,
        String artifactPath,
        String artifactSha256,
        String outputDigest,
        String replayCommand
) {
}
