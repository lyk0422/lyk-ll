package com.example.starter.rebate.repo;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * refund_registration 行。
 */
public record RefundRow(
        Long id,
        Long batchId,
        String treasurySerialNo,
        BigDecimal declaredTax,
        BigDecimal receivedAmount,
        BigDecimal diffRatio,
        String status,
        Instant createdAt
) {
}
