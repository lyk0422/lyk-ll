package com.example.starter.trial.repository;

import java.time.Instant;

/**
 * enrollment_request 幂等记录。
 */
public record EnrollmentRequestRow(
        long id,
        String requestId,
        String trialId,
        String subjectScreenNo,
        String siteCode,
        Instant consentAtUtc,
        String fingerprint,
        long randomizationNo,
        String blindedGroupCode,
        Instant createdAtUtc) {
}
