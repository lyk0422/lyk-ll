package com.example.starter.dao;

import java.time.Instant;

/**
 * 受控出水积分结算台账行。
 */
public record ScoreSettlementRow(
        Long id,
        String diverCode,
        String diveRequestId,
        int planVersion,
        int preDiveScore,
        int bottomMin,
        int residualScore,
        Instant lastSurfacedAtUtc,
        Instant settledAtUtc) {
}
