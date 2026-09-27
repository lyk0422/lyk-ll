package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 采集包证据视图（原始包，append-only）。
 *
 * @param status      BUFFERED/CONSUMED/LATE
 * @param contentHash 包内容 SHA-256 指纹
 */
public record CollectionPacketView(
        int seq,
        String pointCode,
        BigDecimal measuredTorque,
        Instant measuredAt,
        String status,
        String contentHash,
        String requestId,
        Instant createdAt) {
}
