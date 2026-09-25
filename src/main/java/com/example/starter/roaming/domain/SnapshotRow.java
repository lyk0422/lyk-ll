package com.example.starter.roaming.domain;

import java.time.LocalDateTime;

/**
 * 计量快照行。
 */
public record SnapshotRow(
        Long id,
        String sessionNo,
        int meterSeq,
        long meterWh,
        LocalDateTime snapshotAt,
        String requestId,
        LocalDateTime createdAt) {
}
