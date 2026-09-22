package com.example.starter.ros.api;

import java.util.List;

/**
 * 检查点恢复结果视图。
 *
 * @param checkpointId       恢复的检查点标识
 * @param restoredNodes      恢复的节点视图列表
 * @param restoredRequestIds 已恢复的已处理 requestId
 * @param lastEventRevision  恢复后事件流的最大 revision（恢复不会重放已确认事件）
 */
public record RestoreResult(
        String checkpointId,
        List<NodeView> restoredNodes,
        List<String> restoredRequestIds,
        long lastEventRevision) {
}
