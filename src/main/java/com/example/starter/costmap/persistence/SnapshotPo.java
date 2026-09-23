package com.example.starter.costmap.persistence;

/**
 * costmap_snapshot 表行对象（不可变快照）。
 */
public record SnapshotPo(
        String mapId,
        String snapshotId,
        long seq,
        String label,
        int width,
        int height,
        long liveSeq,
        long allocSeq,
        int[] cellData,
        long[] winnerData,
        long createdAt) {
}
