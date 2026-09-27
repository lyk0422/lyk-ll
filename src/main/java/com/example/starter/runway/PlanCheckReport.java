package com.example.starter.runway;

import java.time.Instant;

/**
 * 例行巡检检查回报行：对应 plan_check_report 表。
 *
 * @param id             回报主键
 * @param planId         所属例行计划ID
 * @param reportedAt     回报时间（UTC）
 * @param completedItems 本次回报已完成检查项（JSON数组）
 * @param complete       是否完结回报：真=巡检正常完成，计划关闭
 * @param late           是否迟到登记：真=计划已被抢占，仅登记不开放跑道
 * @param requestId      回报幂等请求标识
 * @param createdAt      记录创建时间（UTC）
 */
public record PlanCheckReport(
        long id,
        long planId,
        Instant reportedAt,
        String completedItems,
        boolean complete,
        boolean late,
        String requestId,
        Instant createdAt) {
}
