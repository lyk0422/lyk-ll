package com.example.starter.pv;

import java.time.Instant;

/**
 * 监管递交批次（对应 pv_submission_batch 表）。
 */
public record PvBatch(
        Long id,
        String batchNo,
        String requestId,
        String fingerprint,
        Instant createdAt) {
}
