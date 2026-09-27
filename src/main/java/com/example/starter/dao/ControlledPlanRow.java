package com.example.starter.dao;

import java.time.Instant;

/**
 * 受控方案冻结快照（潜次 + 方案版本粒度）。
 */
public record ControlledPlanRow(
        Long id,
        String diveRequestId,
        int planVersion,
        String planRequestId,
        Instant atUtc,
        int teamAllowedMin,
        Instant createdAtUtc) {
}
