package com.example.starter.domain;

import java.time.Instant;

/**
 * 证据表行记录。
 */
public record Evidence(
        String evidenceId,
        String sha256,
        Instant collectedAt,
        Instant expiresAt,
        EvidenceStatus status,
        Instant createdAt
) {
}
