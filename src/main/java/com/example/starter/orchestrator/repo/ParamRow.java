package com.example.starter.orchestrator.repo;

/**
 * node_param 表行记录。
 */
public record ParamRow(
        String planId,
        String nodeId,
        String paramKey,
        String paramValue
) {
}
