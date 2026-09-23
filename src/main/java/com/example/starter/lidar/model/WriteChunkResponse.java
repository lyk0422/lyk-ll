package com.example.starter.lidar.model;

/**
 * 写入块的结果。
 *
 * @param chunkId      块 ID
 * @param version      块版本
 * @param source       来源
 * @param compression  实际落盘压缩方式（可能回退为 NONE）
 * @param checksum     规范化未压缩内容 SHA-256（十六进制小写）
 * @param pointCount   点数
 * @param storedSize   落盘字节数
 * @param deduplicated true 表示同 ID 同内容重试，返回的是已有结果未落新盘
 */
public record WriteChunkResponse(
        String chunkId,
        long version,
        String source,
        Compression compression,
        String checksum,
        int pointCount,
        long storedSize,
        boolean deduplicated
) {
}
