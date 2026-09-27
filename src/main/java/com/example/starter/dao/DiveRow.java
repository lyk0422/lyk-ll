package com.example.starter.dao;

import java.time.Instant;

/**
 * 潜次主表行。
 *
 * @param pointsControlled      是否启用反复潜水积分控制（虚构模型）；FALSE 保持旧语义
 * @param controlledStartedAtUtc 受控潜次实际下潜时刻（UTC）；空值表示未实际下潜
 */
public record DiveRow(
        String requestId,
        String applyFingerprint,
        String teamKey,
        String activeTeamSlot,
        String diverACode,
        String diverBCode,
        int plannedDepthM,
        int plannedBottomMin,
        String status,
        int currentPlanVersion,
        Instant surfacedAtUtc,
        boolean symptomatic,
        Instant closedAtUtc,
        boolean pointsControlled,
        Instant controlledStartedAtUtc,
        Instant createdAtUtc,
        Instant updatedAtUtc) {
}
