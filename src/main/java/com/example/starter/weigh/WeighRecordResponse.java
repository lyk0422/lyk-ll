package com.example.starter.weigh;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 过磅响应：deduplicated 表示命中“车牌+检测站+过磅时刻+称重方式”去重，
 * idempotentReplay 表示同 requestId 同参数重放返回首次结果。
 */
public record WeighRecordResponse(
        long id,
        String requestId,
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
        boolean lateEvidence,
        boolean deduplicated,
        boolean idempotentReplay) {

    static WeighRecordResponse of(WeighRecord r, boolean deduplicated, boolean idempotentReplay) {
        return new WeighRecordResponse(r.id(), r.requestId(), r.plateNo(), r.stationId(), r.weighedAt(),
                r.axleType(), r.weighType(), r.totalWeightKg(), r.limitWeightKg(), r.overRate(),
                r.overLimit(), r.authoritative(), r.lateEvidence(), deduplicated, idempotentReplay);
    }
}
