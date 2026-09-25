package com.example.starter.recheck;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 复检申诉响应：outcome 为 OVERTURNED（推翻原处罚）或 UPHELD（维持）。
 */
public record RecheckResponse(
        long id,
        String requestId,
        long penaltyId,
        long caseId,
        int staticWeightKg,
        BigDecimal staticOverRate,
        Instant appliedAt,
        String outcome,
        Long revocationDocumentId,
        Long replacementPenaltyId,
        boolean idempotentReplay) {
}
