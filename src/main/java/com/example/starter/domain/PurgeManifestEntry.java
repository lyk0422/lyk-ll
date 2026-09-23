package com.example.starter.domain;

import java.time.Instant;

/**
 * 不可变清除清单行，最终确认时一次性冻结。
 */
public record PurgeManifestEntry(
        long id,
        String batchId,
        String evidenceId,
        String sha256,
        Instant purgedAt
) {
}
