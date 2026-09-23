package com.example.starter.audit.api;

import java.time.Instant;
import java.util.List;

/**
 * 任务详情视图。
 */
public record TaskView(
        long id,
        String requestId,
        String state,
        String codeVersion,
        String configVersion,
        String mapVersion,
        String rosbagVersion,
        long seed,
        String resultHash,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        List<StageView> stages
) {
}
