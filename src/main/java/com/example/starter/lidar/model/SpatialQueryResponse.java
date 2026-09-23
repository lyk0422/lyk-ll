package com.example.starter.lidar.model;

import java.util.List;

/**
 * 空间查询响应。
 *
 * @param points          命中点，按 (时间, chunkId, 块内点序) 稳定排序
 * @param quarantinedChunks 本次查询中读取失败被隔离的块 ID 列表（损坏块的点从结果排除）
 * @param scannedChunks   实际参与读取（粗筛命中）的块 ID 列表
 */
public record SpatialQueryResponse(
        List<QueryPoint> points,
        List<String> quarantinedChunks,
        List<String> scannedChunks
) {
}
