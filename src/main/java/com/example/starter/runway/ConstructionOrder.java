package com.example.starter.runway;

import java.time.Instant;

/**
 * 施工单行：对应 construction_order 表。
 *
 * @param id          施工单主键
 * @param runwayId    受控跑道编号
 * @param status      当前状态
 * @param version     聚合乐观锁版本：从1开始，每次成功变更加1
 * @param activatedAt 生效时刻（UTC），未生效为 null
 * @param completedAt 全部段验收完成时刻（UTC），未完成为 null
 * @param withdrawnAt 撤回时刻（UTC），未撤回为 null
 * @param requestId   登记幂等请求标识
 * @param createdAt   记录创建时间（UTC）
 * @param updatedAt   记录最近更新时间（UTC）
 */
public record ConstructionOrder(
        long id,
        String runwayId,
        ConstructionOrderStatus status,
        int version,
        Instant activatedAt,
        Instant completedAt,
        Instant withdrawnAt,
        String requestId,
        Instant createdAt,
        Instant updatedAt) {
}
