package com.example.starter.enrollment;

import java.time.Instant;

/**
 * 入组记录。status：ENROLLED=在组，UNBLINDED=已揭盲（终态）。所有时刻均为 UTC。
 */
public record Enrollment(
        long id,
        long trialId,
        String requestId,
        String fingerprint,
        String screeningNo,
        long centerId,
        Instant consentSignedAt,
        long randomNo,
        char blindedArm,
        char actualArm,
        String status,
        Instant createdAt) {

    public boolean isUnblinded() {
        return "UNBLINDED".equals(status);
    }
}
