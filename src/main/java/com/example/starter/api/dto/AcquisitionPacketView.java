package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 采集原始包证据视图。
 *
 * @param status      BUFFERED=乱序缓冲，CONSUMED=已入账，LATE=归档后迟到
 * @param contentHash 包内容 SHA-256 指纹（点号/力矩/测量时刻）
 */
public record AcquisitionPacketView(
        int seq,
        String pointCode,
        BigDecimal measuredTorque,
        Instant measuredAt,
        String status,
        long batchId,
        String requestId,
        String contentHash,
        Instant createdAt,
        Instant consumedAt) {
}
