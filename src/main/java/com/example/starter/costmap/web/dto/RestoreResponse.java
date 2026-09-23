package com.example.starter.costmap.web.dto;

/**
 * 快照恢复结果：当前查询视图被替换为快照发布时刻的视图。
 */
public record RestoreResponse(
        String mapId,
        String snapshotId,
        long restoredLiveSeq,
        long restoredAllocSeq,
        long restoredStatementCount) {
}
