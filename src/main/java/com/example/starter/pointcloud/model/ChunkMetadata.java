package com.example.starter.pointcloud.model;

/**
 * 已发布块的元数据，同时作为 H2 索引表的内存投影。
 *
 * @param chunkId        块唯一标识
 * @param version        块版本
 * @param source         数据来源（如雷达型号/节点）
 * @param startNanos     块内最早点时间戳，纳秒（含）
 * @param endNanos       块内最晚点时间戳，纳秒（含）
 * @param bbox           三维包围盒，闭区间
 * @param pointCount     点数
 * @param compression    实际落盘使用的压缩方式（可能因回退与请求不同）
 * @param checksum       规范化未压缩内容的 SHA-256，十六进制小写
 * @param fileName       已发布块文件名
 */
public record ChunkMetadata(String chunkId,
                            long version,
                            String source,
                            long startNanos,
                            long endNanos,
                            BoundingBox bbox,
                            int pointCount,
                            Compression compression,
                            String checksum,
                            String fileName) {
}
