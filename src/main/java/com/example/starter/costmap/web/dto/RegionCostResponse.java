package com.example.starter.costmap.web.dto;

/**
 * 区域代价查询响应；costs[y][x] 为相对区域偏移的代价矩阵（0..254）。
 * owners[y][x] 为该格被障碍物直接占据时的中标来源ID（膨胀波及但未被占据的格点为 null），
 * 中标决胜顺序公开确定：priority 降序、timestampMs 降序、sourceId 字典序升序。
 */
public record RegionCostResponse(
        String versionId,
        String snapshotId,
        String viewId,
        long seq,
        int xMin,
        int yMin,
        int xMax,
        int yMax,
        int[][] costs,
        String[][] owners) {
}
