package com.example.starter.runway.dto;

import java.time.Instant;
import java.util.List;

/**
 * 施工单响应。
 *
 * @param id          施工单ID
 * @param runwayId    受控跑道编号
 * @param status      状态：PENDING/ACTIVE/COMPLETED/WITHDRAWN
 * @param version     聚合乐观锁版本
 * @param segments    施工段视图集合（按段号排序）
 * @param activatedAt 生效时刻（UTC），未生效为 null
 * @param completedAt 全部段验收完成时刻（UTC），未完成为 null
 * @param withdrawnAt 撤回时刻（UTC），未撤回为 null
 * @param createdAt   登记时间（UTC）
 * @param updatedAt   最近变更时间（UTC）
 */
public record ConstructionOrderResponse(
        long id,
        String runwayId,
        String status,
        int version,
        List<SegmentView> segments,
        Instant activatedAt,
        Instant completedAt,
        Instant withdrawnAt,
        Instant createdAt,
        Instant updatedAt) {
}
