package com.example.starter.api;

import com.example.starter.repo.PlanRow;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 加钞计划响应/台账行。金额单位为元；各时间字段为 UTC 时刻，NULL 表示对应环节尚未发生。
 * late=true 表示该请求在日结封存后才到达，仅登记迟到事件，未改变计划状态。
 */
public record PlanResponse(
        Long id,
        String planNo,
        String atmCode,
        LocalDate businessDate,
        long predictedAmount,
        long plannedAmount,
        String status,
        Instant approvedAt,
        Instant escortDeadline,
        Instant escortCompletedAt,
        int checkinCount,
        Long countedAmount,
        Instant countedAt,
        Long deviceAmount,
        Instant deviceConfirmedAt,
        Instant reconciledAt,
        Long discrepancyId,
        Instant sealedAt,
        boolean late,
        String message
) {
    public static PlanResponse of(PlanRow p, String atmCode, int checkinCount, boolean late, String message) {
        return new PlanResponse(
                p.getId(),
                p.getPlanNo(),
                atmCode,
                p.getBusinessDate(),
                p.getPredictedAmount(),
                p.getPlannedAmount(),
                p.getStatus().name(),
                p.getApprovedAt(),
                p.getEscortDeadline(),
                p.getEscortCompletedAt(),
                checkinCount,
                p.getCountedAmount(),
                p.getCountedAt(),
                p.getDeviceAmount(),
                p.getDeviceConfirmedAt(),
                p.getReconciledAt(),
                p.getDiscrepancyId(),
                p.getSealedAt(),
                late,
                message);
    }
}
