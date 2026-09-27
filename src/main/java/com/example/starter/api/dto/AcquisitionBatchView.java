package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 采集上传批次证据视图。
 */
public record AcquisitionBatchView(
        long batchId,
        String requestId,
        int packetCount,
        int insertedCount,
        int consumedCount,
        int lateCount,
        int watermarkAfter,
        Instant createdAt) {
}
