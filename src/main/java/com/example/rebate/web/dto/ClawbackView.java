package com.example.rebate.web.dto;

import java.time.Instant;

/**
 * 冲回单视图。金额字段单位均为分。
 */
public record ClawbackView(
        Long id,
        String batchNo,
        String declarationNo,
        String externalCorrectionNo,
        String reason,
        long amountCents,
        long frozenTaxCents,
        String status,
        String submittedBy,
        String reviewer,
        Instant reviewedAt,
        Integer version,
        Instant createdAt,
        Instant updatedAt) {
}
