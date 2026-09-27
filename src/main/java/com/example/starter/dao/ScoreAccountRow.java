package com.example.starter.dao;

import java.time.Instant;

/**
 * 潜水员反复潜水积分账户行（虚构积分模型，不用于真实潜水减压）。
 *
 * @param activeDiveRequestId 当前占用该潜水员的受控潜次；空表示空闲
 */
public record ScoreAccountRow(
        String diverCode,
        int version,
        int residualScore,
        Instant lastSurfacedAtUtc,
        String lastDiveRequestId,
        String activeDiveRequestId,
        Instant createdAtUtc,
        Instant updatedAtUtc) {
}
