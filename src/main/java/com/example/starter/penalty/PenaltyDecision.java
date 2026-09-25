package com.example.starter.penalty;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 处罚决定领域行：对超限检测记录作出的处置文书。
 */
public record PenaltyDecision(
        long id,
        String requestId,
        String fingerprint,
        long caseId,
        long weighRecordId,
        String plateNo,
        BigDecimal overRate,
        String measure,
        BigDecimal fineAmount,
        int demeritPoints,
        String status,
        Instant servedAt) {
}
