package com.example.starter.runway;

import java.time.Instant;

/**
 * 施工段行：对应 construction_segment 表。半开整数米区间 [startMeters, endMeters)。
 *
 * @param id            施工段主键
 * @param orderId       所属施工单ID
 * @param segmentNo     段号：单内唯一
 * @param startMeters   段起点（单位：米，含）
 * @param endMeters     段终点（单位：米，不含）
 * @param status        当前状态
 * @param completedAt   段验收完成时刻（UTC），未完成为 null
 * @param createdAt     记录创建时间（UTC）
 * @param updatedAt     记录最近更新时间（UTC）
 */
public record ConstructionSegment(
        long id,
        long orderId,
        int segmentNo,
        int startMeters,
        int endMeters,
        ConstructionSegmentStatus status,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {
}
