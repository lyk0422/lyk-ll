package com.example.starter.rights.dto;

import java.time.Instant;

/**
 * 历史授权证据（播出后冻结的快照）。
 */
public record EvidenceView(
        String submissionId,
        int index,
        Instant airStart,
        Instant airEnd,
        String licenseId,
        int licenseVersion,
        Instant windowStart,
        Instant windowEnd,
        String mode,
        Instant completedAt,
        String requestId) {
}
