package com.example.starter.orchestrator.repo;

import java.time.LocalDateTime;

/**
 * launch_plan 表行记录。
 */
public record PlanRow(
        Long id,
        String planId,
        String version,
        String requestId,
        String planName,
        String status,
        String payloadHash,
        boolean cancelRequested,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
