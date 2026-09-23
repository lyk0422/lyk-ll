package com.example.starter.ros;

import java.util.Map;

/**
 * 单个节点在一次热更新事务中的变更集。
 *
 * @param expectedVersion 调用方期望的节点当前已提交版本；与库中不一致即版本冲突
 * @param values          待写入参数（参数名 -> 新值）；null 表示将该参数重置为默认值
 */
public record NodeChange(long expectedVersion, Map<String, Object> values) {
}
