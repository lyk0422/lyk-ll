package com.example.starter.weigh;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 检测记录领域行：车辆过磅产生的称重判定数据。
 */
public record WeighRecord(
        Long id,
        String requestId,
        String fingerprint,
        String plateNo,
        String stationId,
        Instant weighedAt,
        String axleType,
        String weighType,
        int totalWeightKg,
        int limitWeightKg,
        BigDecimal overRate,
        boolean overLimit,
        boolean authoritative,
        boolean lateEvidence) {
}
