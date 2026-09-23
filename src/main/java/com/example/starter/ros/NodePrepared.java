package com.example.starter.ros;

import java.util.Map;

/**
 * 事务准备阶段为单个节点计算出的上下文。
 *
 * @param nodeId          节点 ID
 * @param expectedVersion 请求携带的期望版本
 * @param currentVersion  准备时读到的当前已提交版本
 * @param candidate       整笔更新后的候选完整快照（强类型，已合并默认值）
 * @param changedText     本次落库变更（参数名 -> 规范化文本；null 表示重置为默认值）
 */
public record NodePrepared(
        String nodeId,
        long expectedVersion,
        long currentVersion,
        Map<String, Object> candidate,
        Map<String, String> changedText) {

    public boolean versionMatches() {
        return expectedVersion == currentVersion;
    }
}
