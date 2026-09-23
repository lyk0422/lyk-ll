package com.example.starter.pointcloud.api;

import com.example.starter.pointcloud.model.Compression;

/**
 * 写入块响应。
 *
 * @param chunkId     块唯一标识
 * @param version     块版本
 * @param source      数据来源
 * @param pointCount  点数
 * @param compression 实际落盘压缩方式
 * @param checksum    规范化未压缩内容的 SHA-256，十六进制小写
 * @param deduplicated 是否为同 ID 同内容重试：true 表示直接返回首次结果，未重复落盘
 */
public record WriteChunkResponse(String chunkId,
                                 long version,
                                 String source,
                                 int pointCount,
                                 Compression compression,
                                 String checksum,
                                 boolean deduplicated) {
}
