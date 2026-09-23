package com.example.starter.domain;

import java.time.Instant;
import java.util.List;

/**
 * 清除批次及其候选清单（rescued=true 表示最终确认时被救回）。
 */
public record PurgeBatch(
        String batchId,
        String markRequestId,
        Instant cutoffTime,
        Long expectedVersion,
        BatchStatus status,
        List<PurgeBatchItem> items,
        Instant createdAt,
        Instant finalizedAt
) {
}
