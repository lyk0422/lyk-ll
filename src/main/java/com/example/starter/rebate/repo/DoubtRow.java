package com.example.starter.rebate.repo;

import java.time.Instant;

/**
 * doubt 行。
 */
public record DoubtRow(
        Long id,
        Long batchId,
        Long docId,
        String status,
        String explanation,
        String explainRequestId,
        Instant createdAt,
        Instant explainedAt,
        Instant reviewedAt
) {
}
