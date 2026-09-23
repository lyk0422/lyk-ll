package com.example.starter.lidar.model;

import java.time.Instant;

/**
 * 块元数据（H2 索引行的内存表示）。
 * <p>坐标包围盒单位与点坐标一致；时间为 epoch 毫秒，空值不允许（空块禁止写入）。</p>
 *
 * @param chunkId    块唯一标识（主键）
 * @param version    块版本（非负），写入请求给出
 * @param source     来源标识
 * @param startTs    块内最早点时间（epoch 毫秒，含）
 * @param endTs      块内最晚点时间（epoch 毫秒，含）
 * @param bbox       三维闭区间包围盒
 * @param pointCount 点数（正整数）
 * @param compression 实际落盘压缩方式（可能相对请求回退为 NONE）
 * @param checksum   规范化未压缩内容的 SHA-256（十六进制小写）；NONE/GZIP 必须一致
 * @param fileName   块文件名（相对存储根目录）
 * @param storedSize 落盘字节数（压缩后或原始字节数）
 * @param createdAt  索引创建时间
 */
public record ChunkMetadata(
        String chunkId,
        long version,
        String source,
        long startTs,
        long endTs,
        BBox bbox,
        int pointCount,
        Compression compression,
        String checksum,
        String fileName,
        long storedSize,
        Instant createdAt
) {
}
