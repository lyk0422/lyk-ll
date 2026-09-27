package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 例行完成回报结果。
 *
 * @param planId      计划ID
 * @param status      回报后计划状态（正常完成为 COMPLETED；迟到登记时保持 ABORTED）
 * @param version     计划聚合版本（正常完成加1；迟到登记不变）
 * @param late        是否迟到登记：true=紧急中止后收到，仅记录，不开放跑道
 * @param completedAt 完成时刻（UTC）
 */
public record PlanCompleteResponse(
        long planId,
        String status,
        int version,
        boolean late,
        Instant completedAt) {
}
