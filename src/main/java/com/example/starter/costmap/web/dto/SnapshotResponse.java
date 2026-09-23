package com.example.starter.costmap.web.dto;

/**
 * 快照元数据响应（不含全图数据，避免大载荷）。
 */
public record SnapshotResponse(
        String mapId,
        String snapshotId,
        long seq,
        String label,
        int width,
        int height,
        long liveSeq,
        long statementCount,
        long createdAt) {
}
