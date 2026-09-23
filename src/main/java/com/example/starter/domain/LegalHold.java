package com.example.starter.domain;

import java.time.Instant;
import java.util.List;

/**
 * 法律保全单及其显式证据集合。
 */
public record LegalHold(
        long holdId,
        String caseId,
        HoldStatus status,
        long version,
        List<String> evidenceIds,
        String createRequestId,
        String releaseRequestId,
        Instant createdAt,
        Instant releasedAt
) {
}
