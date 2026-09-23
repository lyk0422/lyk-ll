package com.example.starter.slam.po;

import java.time.Instant;

/**
 * 位姿图聚合根，记录当前有效图版本（每写入一个关键帧或一条 VALID 边递增 1）。
 *
 * @param graphId        图 ID
 * @param currentVersion 当前有效图版本；REJECTED 回环不改变该版本
 * @param createdAt      创建时间（UTC）
 * @param updatedAt      最近更新时间（UTC）
 */
public record PoseGraphPO(
        String graphId,
        long currentVersion,
        Instant createdAt,
        Instant updatedAt
) {
}
