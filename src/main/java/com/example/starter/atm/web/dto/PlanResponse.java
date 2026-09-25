package com.example.starter.atm.web.dto;

import com.example.starter.atm.domain.ReplenishPlan;

/**
 * 计划视图响应。金额单位：分；时间字段为 epoch 毫秒（UTC），null 表示该环节尚未发生。
 */
public record PlanResponse(
        Long id,
        String planNo,
        String atmNo,
        String boxNo,
        String planUtcDate,
        Long predictedAmount,
        Long plannedAmount,
        String status,
        Long approvedAt,
        Long escortDeadline,
        Long countedAmount,
        Long countedAt,
        String countGuard1No,
        String countGuard2No,
        Long deviceAmount,
        Long deviceConfirmedAt,
        Long reconciledAt,
        Long overShortId,
        Long sealedAt,
        boolean late
) {
    public static PlanResponse of(ReplenishPlan p, String atmNo, String boxNo,
                                  String guard1No, String guard2No, boolean late) {
        return new PlanResponse(
                p.getId(), p.getPlanNo(), atmNo, boxNo, p.getPlanUtcDate(),
                p.getPredictedAmount(), p.getPlannedAmount(), p.getStatus().name(),
                p.getApprovedAt(), p.getEscortDeadline(),
                p.getCountedAmount(), p.getCountedAt(), guard1No, guard2No,
                p.getDeviceAmount(), p.getDeviceConfirmedAt(),
                p.getReconciledAt(), p.getOverShortId(), p.getSealedAt(), late);
    }
}
