package com.example.starter.runway.dto;

import java.time.Instant;
import java.util.List;

/**
 * 施工单视图。
 *
 * @param orderId     施工单主键
 * @param runwayId    受控跑道编号
 * @param status      状态：DRAFT/EFFECTIVE/COMPLETED/WITHDRAWN
 * @param version     聚合乐观锁版本
 * @param segments    施工段集合（按段号排序）
 * @param activatedAt 生效时刻（UTC），未生效为null
 * @param completedAt 全部段验收完成时刻（UTC），未结束为null
 * @param withdrawnAt 撤回时刻（UTC），未撤回为null
 * @param evidence    生效时冻结的抢占证据JSON
 */
public record ConstructionOrderResponse(
        long orderId,
        String runwayId,
        String status,
        int version,
        List<SegmentView> segments,
        Instant activatedAt,
        Instant completedAt,
        Instant withdrawnAt,
        String evidence) {
}
