package com.example.starter.lc.repo;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 交单批次表行对象。
 */
public record SubmissionRow(
        Long id,
        String batchNo,
        String lcNo,
        String beneficiary,
        BigDecimal drawAmount,
        String status,
        Instant presentedAt,
        Instant deadlineAt,
        Instant refusedAt,
        Instant acceptedAt,
        Instant paidAt,
        Instant createdAt) {
}
