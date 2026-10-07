package com.example.starter.runway;

import java.time.Instant;

/**
 * 施工单：引用受控跑道与1至10个不同段号；生效冻结区间并阻断整跑道新使用。
 *
 * @param id          施工单主键
 * @param runwayId    受控跑道编号
 * @param status      状态：DRAFT/EFFECTIVE/COMPLETED/WITHDRAWN
 * @param version     聚合乐观锁版本：新增为1，成功变更加1
 * @param evidence    生效时冻结的抢占证据JSON（被取消/中止的例行计划清单）
 * @param activatedAt 生效时刻（UTC），未生效为NULL
 * @param completedAt 全部段验收完成时刻（UTC），未结束为NULL
 * @param withdrawnAt 撤回时刻（UTC），未撤回为NULL
 * @param requestId   登记幂等请求标识
 * @param createdAt   记录创建时间（UTC）
 * @param updatedAt   记录最近更新时间（UTC）
 */
public record ConstructionOrder(
        long id,
        String runwayId,
        ConstructionOrderStatus status,
        int version,
        String evidence,
        Instant activatedAt,
        Instant completedAt,
        Instant withdrawnAt,
        String requestId,
        Instant createdAt,
        Instant updatedAt) {
}
