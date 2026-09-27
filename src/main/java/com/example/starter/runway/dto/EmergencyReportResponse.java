package com.example.starter.runway.dto;

import java.util.List;

/**
 * 紧急关闭接报结果：携带同事务被取消（未开始）与被中止（已开始）的计划ID证据。
 *
 * @param emergencyId      紧急事件ID
 * @param runwayId         跑道编号
 * @param status           固定 ACTIVE
 * @param version          紧急事件版本号（从1开始）
 * @param startedAt        紧急关闭生效时刻（UTC）
 * @param cancelledPlanIds 被取消的尚未开始计划ID
 * @param abortedPlanIds   被中止的进行中计划ID（已完成检查项保留）
 */
public record EmergencyReportResponse(
        long emergencyId,
        String runwayId,
        String status,
        int version,
        java.time.Instant startedAt,
        List<Long> cancelledPlanIds,
        List<Long> abortedPlanIds) {
}
