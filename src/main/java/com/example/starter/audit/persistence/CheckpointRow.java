package com.example.starter.audit.persistence;

import java.time.Instant;

/**
 * task_checkpoint 表行记录。
 * 每个成功检查点保存当时的全部绑定版本快照、上游产物摘要（upstream_digest，
 * 首阶段为固定根摘要）与本阶段输出摘要（output_digest）。
 */
public record CheckpointRow(
        Long id,
        long taskId,
        String stage,
        int attempt,
        String codeVersion,
        String configVersion,
        String mapVersion,
        String rosbagVersion,
        long seed,
        String upstreamDigest,
        String outputDigest,
        String artifactSha256,
        String metricsJson,
        Instant createdAt
) {
}
