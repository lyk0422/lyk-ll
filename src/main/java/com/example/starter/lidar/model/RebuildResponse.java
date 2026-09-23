package com.example.starter.lidar.model;

import java.util.List;

/**
 * 索引重建结果。
 *
 * @param filesScanned      块目录扫描到的文件数
 * @param chunksIndexed     本次重建新插入索引的块数
 * @param badFilesQuarantined 损坏并隔离的文件数
 * @param staleRowsDeleted  无对应文件而被清除的索引行数
 * @param totalChunks       重建完成后索引中的总块数（含重建期间增量写入）
 */
public record RebuildResponse(
        int filesScanned,
        int chunksIndexed,
        int badFilesQuarantined,
        int staleRowsDeleted,
        long totalChunks,
        List<String> quarantinedFiles
) {
}
