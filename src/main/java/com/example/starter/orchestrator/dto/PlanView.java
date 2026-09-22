package com.example.starter.orchestrator.dto;

import java.util.List;

/**
 * 计划视图。
 *
 * @param planId 计划标识
 * @param version 计划版本
 * @param requestId 幂等请求标识
 * @param status 计划状态
 * @param idempotentReplay 本次提交是否为幂等重放
 * @param nodes 节点视图列表
 */
public record PlanView(
        String planId,
        int version,
        String requestId,
        String status,
        boolean idempotentReplay,
        List<NodeView> nodes) {
}
