package com.example.starter.orchestrator.api;

import java.util.List;

/**
 * 计划提交/查询响应：包含计划状态、各节点状态及事件序列。
 */
public record PlanResponse(
        String planId,
        String version,
        String requestId,
        String status,
        boolean cancelRequested,
        List<NodeView> nodes,
        List<EventView> events
) {
    public record NodeView(
            String nodeId,
            String nodeName,
            String state,
            String healthStatus,
            boolean compensated,
            String failureReason,
            List<String> dependsOn
    ) {
    }

    public record EventView(
            Long seq,
            String nodeId,
            String eventType,
            String detail,
            String eventTime
    ) {
    }
}
