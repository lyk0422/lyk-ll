package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 采集批次证据视图。
 */
public record AcquisitionBatchView(
        String batchNo,
        int packetCount,
        int acceptedCount,
        int replayedCount,
        int committedCount,
        int lateCount,
        int watermarkAfter,
        Instant createdAt) {
}
