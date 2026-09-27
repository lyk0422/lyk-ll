package com.example.starter.dao;

import java.time.Instant;

/**
 * 受控方案中每名潜水员的冻结项：最近潜次版本、出水时刻与积分。
 */
public record ControlledPlanDiverRow(
        Long id,
        String diveRequestId,
        int planVersion,
        String diverCode,
        int expectedAccountVersion,
        Instant lastSurfacedAtUtc,
        int residualScore,
        int currentScore,
        long surfaceMinutes,
        int allowedMin,
        int ndlMinutes,
        Instant createdAtUtc) {
}
