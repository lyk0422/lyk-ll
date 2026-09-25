package com.example.starter.rebate.repo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * rebate_batch 行。
 */
public record BatchRow(
        Long id,
        String batchNo,
        LocalDate declareDate,
        String status,
        BigDecimal taxTotal,
        Instant createdAt,
        Instant declaredAt,
        Instant approvedAt,
        Instant refundedAt,
        Instant voidedAt,
        Instant closedAt
) {
}
