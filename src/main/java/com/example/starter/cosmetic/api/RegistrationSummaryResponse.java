package com.example.starter.cosmetic.api;

import java.time.Instant;

/**
 * 变更链中的备案版本摘要。
 */
public record RegistrationSummaryResponse(
        long registrationId,
        String registrationNo,
        int version,
        String changeType,
        Long previousId,
        String status,
        Instant effectiveAt,
        Instant cancelledAt,
        Instant createdAt) {
}
