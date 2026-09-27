package com.example.starter.dao;

import java.time.Instant;

/**
 * 潜次主表行。
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
        boolean controlled,
        Instant closedAtUtc,
        Instant createdAtUtc,
        Instant updatedAtUtc) {
}
