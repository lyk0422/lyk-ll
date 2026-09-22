package com.example.starter.orchestrator.dto;

/**
 * 节点视图。
 *
 * @param key 节点标识
 * @param state 节点状态
 * @param compensated 补偿是否已执行
 */
public record NodeView(String key, String state, boolean compensated) {
}
