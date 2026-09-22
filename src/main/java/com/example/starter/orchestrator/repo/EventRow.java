package com.example.starter.orchestrator.repo;

import java.time.LocalDateTime;

/**
 * plan_event 表行记录（H2 事件日志）。
 */
public record EventRow(
        Long id,
        String planId,
        String version,
        String requestId,
        String nodeId,
        String eventType,
        String detail,
        LocalDateTime eventTime
) {
}
