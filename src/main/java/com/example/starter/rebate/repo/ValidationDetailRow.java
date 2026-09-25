package com.example.starter.rebate.repo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * validation_detail 行。
 */
public record ValidationDetailRow(
        Long id,
        Long batchId,
        Long docId,
        String hsCode,
        BigDecimal rate,
        LocalDate rateEffectiveDate,
        BigDecimal taxBase,
        BigDecimal cost,
        String result,
        Instant createdAt
) {
}
