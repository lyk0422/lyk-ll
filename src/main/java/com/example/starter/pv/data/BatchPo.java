package com.example.starter.pv.data;

import java.time.Instant;

/**
 * pv_submission_batch 表行对象（递交批次头）。
 */
public record BatchPo(
        Long id,
        String batchNo,
        String requestId,
        String requestFingerprint,
        int caseCount,
        Instant createdAt) {
}
