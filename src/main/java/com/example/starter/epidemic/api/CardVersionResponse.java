package com.example.starter.epidemic.api;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 历史版本条目（不可变快照）。
 */
public record CardVersionResponse(
        int versionNo,
        String changeType,
        String diseaseCode,
        String diseaseName,
        String category,
        String caseType,
        String patientName,
        LocalDate onsetDate,
        Instant diagnosisTime,
        boolean late,
        String lateReason,
        String requestId,
        Instant submittedAt
) {
}
