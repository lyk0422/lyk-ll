package com.example.starter.ros.web.dto;

import java.time.LocalDateTime;

/**
 * 检查点视图。
 */
public record CheckpointResponse(
        String nodeId,
        String state,
        long version,
        int resourceCount,
        int processedRequestCount,
        LocalDateTime savedAt
) {
}
