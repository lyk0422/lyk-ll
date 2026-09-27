package com.example.starter.runway;

import java.time.Instant;

/**
 * 例行巡检完成回报行：对应 routine_completion_report 表。
 *
 * @param id          完成回报主键
 * @param planId      所属例行计划ID
 * @param completedAt 回报的巡检完成时刻（UTC）
 * @param late        是否迟到完成：true=计划已被取消/中止后回报，仅登记不开放跑道
 * @param requestId   完成回报幂等请求标识
 * @param createdAt   记录创建时间（UTC）
 */
public record RoutineCompletionReport(
        long id,
        long planId,
        Instant completedAt,
        boolean late,
        String requestId,
        Instant createdAt) {
}
