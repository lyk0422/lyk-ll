package com.example.starter.orchestrator.repo;

/**
 * node_dependency 表行记录。depKind：EXPLICIT / CONDITION。
 */
public record DependencyRow(
        String planId,
        String nodeId,
        String dependsOn,
        String depKind
) {
}
