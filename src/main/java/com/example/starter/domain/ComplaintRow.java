package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 投诉登记行。所有时刻为 UTC。
 */
public record ComplaintRow(
        long id,
        String requestId,
        String stationId,
        BigDecimal frequencyMhz,
        Instant sampledAt,
        BigDecimal fieldStrengthDbuv,
        String evidenceRef,
        String fingerprint,
        Instant createdAt) {
}
