package com.example.starter.runway;

import java.time.Instant;

/**
 * 例行完成回报行：对应 plan_completion 表。
 *
 * @param id          完成回报主键
 * @param planId      所属例行计划ID
 * @param completedAt 完成精确时刻（UTC）
 * @param late        是否迟到登记：true=紧急中止后收到，仅登记不开放跑道
 * @param requestId   完成回报幂等请求标识
 * @param createdAt   记录创建时间（UTC）
 */
public record PlanCompletionRecord(
        long id,
        long planId,
        Instant completedAt,
        boolean late,
        String requestId,
        Instant createdAt) {
}
