package com.example.starter.rosconfig.dto;

/**
 * 单个节点在事务中的处理结果。
 *
 * @param node       节点名
 * @param status     结果状态
 * @param message    拒绝/失败原因，成功时为 null
 * @param oldVersion 事务前节点版本
 * @param newVersion 提交成功后的节点版本，未提交时为 null
 */
public record NodeResult(
        String node,
        NodeResultStatus status,
        String message,
        Long oldVersion,
        Long newVersion
) {
    public static NodeResult of(String node, NodeResultStatus status, String message, Long oldVersion, Long newVersion) {
        return new NodeResult(node, status, message, oldVersion, newVersion);
    }
}
