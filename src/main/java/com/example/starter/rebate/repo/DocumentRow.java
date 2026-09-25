package com.example.starter.rebate.repo;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * rebate_document 行。
 */
public record DocumentRow(
        Long id,
        String declarationNo,
        String invoiceNo,
        String hsCode,
        BigDecimal fobRmb,
        BigDecimal fobUsd,
        String status,
        Long batchId,
        Instant collectedAt,
        Instant releasedAt
) {
}
