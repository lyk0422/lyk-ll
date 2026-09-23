package com.example.starter.slam.api;

import java.time.Instant;

/**
 * 关键帧写入响应。
 *
 * @param id           关键帧 ID
 * @param source       数据来源
 * @param version      数据版本
 * @param graphId      所属图 ID
 * @param duplicated   是否为同 ID 同内容的重复写入（true 时返回原记录）
 * @param createdAt    创建时间（UTC）
 */
public record KeyFrameView(
        String id,
        String source,
        long version,
        String graphId,
        boolean duplicated,
        Instant createdAt
) {
}
