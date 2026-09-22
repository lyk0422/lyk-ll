package com.example.starter.orchestrator.repo;

/**
 * node_condition 表行记录。
 */
public record ConditionRow(
        String planId,
        String nodeId,
        String predicate,
        String refNodeId,
        String paramName,
        String expectedValue
) {
}
