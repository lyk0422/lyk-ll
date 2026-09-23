package com.example.starter.pointcloud.api;

/**
 * 索引重建响应。
 *
 * @param scannedChunks 从块文件扫描到的块数（含重建期间增量）
 * @param reindexed     实际重新写入索引的块数
 * @param quarantined   扫描中发现损坏并隔离的块 ID 列表
 */
public record ReindexResponse(int scannedChunks,
                              int reindexed,
                              java.util.List<String> quarantined) {
}
