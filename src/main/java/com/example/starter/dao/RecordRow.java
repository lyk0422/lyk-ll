package com.example.starter.dao;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 水下记录行。
 */
public record RecordRow(
        Long id,
        String diveRequestId,
        int planVersion,
        String diverCode,
        String phase,
        int fromDepthM,
        int toDepthM,
        int durationMin,
        BigDecimal rateMMin,
        boolean effective,
        boolean frozen,
        Long pairedRecordId,
        Instant eventTimeUtc,
        Instant createdAtUtc) {
}
