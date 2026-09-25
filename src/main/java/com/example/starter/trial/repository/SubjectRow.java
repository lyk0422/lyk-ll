package com.example.starter.trial.repository;

import java.time.Instant;

/**
 * trial_subject 行记录。时间列以 UTC 时刻存储。
 */
public record SubjectRow(
        long id,
        String trialId,
        String siteCode,
        String subjectScreenNo,
        long randomizationNo,
        String blindedGroupCode,
        String assignedGroup,
        Instant consentAtUtc,
        Instant enrolledAtUtc,
        Instant unblindedAtUtc,
        String status) {
}
