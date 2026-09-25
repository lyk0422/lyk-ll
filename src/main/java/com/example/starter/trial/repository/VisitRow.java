package com.example.starter.trial.repository;

import java.time.Instant;

/**
 * visit_record 访视记录。
 *
 * @param excludedFromMainAnalysis 1=排除出主要分析集
 * @param exclusionReason          排除原因（未排除为 null）
 */
public record VisitRow(
        long id,
        String trialId,
        String subjectScreenNo,
        Instant visitAtUtc,
        String visitType,
        Double measureValue,
        boolean excludedFromMainAnalysis,
        String exclusionReason,
        Instant createdAtUtc) {
}
