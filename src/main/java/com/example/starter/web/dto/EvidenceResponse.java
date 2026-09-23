package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 证据状态响应。
 */
public record EvidenceResponse(
        String evidenceId,
        String sha256,
        Instant collectedAt,
        Instant expiresAt,
        String status,
        Instant createdAt
) {
}
