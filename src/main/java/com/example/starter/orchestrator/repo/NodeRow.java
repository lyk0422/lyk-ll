package com.example.starter.orchestrator.repo;

import java.time.LocalDateTime;

/**
 * plan_node 表行记录。
 */
public record NodeRow(
        Long id,
        String planId,
        String nodeId,
        String nodeName,
        int orderIndex,
        long launchDelayMs,
        boolean simulateStartFailure,
        boolean healthShouldFail,
        String healthMessage,
        String compensation,
        boolean simulateCompFailure,
        String state,
        boolean compensated,
        String failureReason,
        String healthStatus,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
    /**
     * 返回运行时字段被替换后的拷贝（静态定义字段保持不变）。
     */
    public NodeRow withRuntime(String state, boolean compensated, String failureReason,
                               String healthStatus, LocalDateTime startedAt,
                               LocalDateTime finishedAt) {
        return new NodeRow(id, planId, nodeId, nodeName, orderIndex, launchDelayMs,
                simulateStartFailure, healthShouldFail, healthMessage, compensation,
                simulateCompFailure, state, compensated, failureReason, healthStatus,
                startedAt, finishedAt);
    }
}
