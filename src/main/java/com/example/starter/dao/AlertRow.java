package com.example.starter.dao;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 速率超速告警行。
 */
public record AlertRow(
        Long id,
        String diveRequestId,
        int planVersion,
        String diverCode,
        String phase,
        BigDecimal rateMMin,
        Instant eventTimeUtc,
        Instant createdAtUtc) {
}
