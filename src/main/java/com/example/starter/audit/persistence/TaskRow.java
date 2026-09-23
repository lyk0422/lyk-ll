package com.example.starter.audit.persistence;

import java.time.Instant;

/**
 * task_run 表行记录。
 * 各字段含义：
 * state 任务状态（PENDING/RUNNING/SUCCESS/FAILED/INTERRUPTED）；
 * seed 随机种子；request_hash 为创建参数摘要，用于同键异参冲突判定；
 * result_hash 为最终阶段摘要哈希；时间均为 UTC（Instant），空值表示对应时刻尚未发生。
 */
public record TaskRow(
        Long id,
        String requestId,
        String requestHash,
        String codeVersion,
        String configVersion,
        String mapVersion,
        String rosbagVersion,
        long seed,
        String metricsProfile,
        String faultPlan,
        String state,
        String resultHash,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
}
