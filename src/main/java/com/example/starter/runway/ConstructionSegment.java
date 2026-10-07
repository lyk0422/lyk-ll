package com.example.starter.runway;

import java.time.Instant;

/**
 * 施工段：半开整数米区间 [startMeter, endMeter)；同单内部重叠拒绝，端点相接允许。
 *
 * @param id         施工段主键
 * @param orderId    所属施工单ID
 * @param segmentNo  段号：单内唯一
 * @param startMeter 区间起点（单位：米，含）
 * @param endMeter   区间终点（单位：米，不含）
 * @param status     状态：PENDING/LOCKED/CLEARED
 * @param clearedAt  验收解除闭锁时刻（UTC），未解除为NULL
 * @param createdAt  记录创建时间（UTC）
 * @param updatedAt  记录最近更新时间（UTC）
 */
public record ConstructionSegment(
        long id,
        long orderId,
        int segmentNo,
        int startMeter,
        int endMeter,
        ConstructionSegmentStatus status,
        Instant clearedAt,
        Instant createdAt,
        Instant updatedAt) {
}
