package com.example.starter.rebate.repo;

import java.time.Instant;

/**
 * late_event 行。
 */
public record LateEventRow(
        Long id,
        Long batchId,
        String declarationNo,
        String invoiceNo,
        String payload,
        Instant createdAt
) {
}
