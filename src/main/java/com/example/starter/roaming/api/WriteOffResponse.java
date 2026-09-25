package com.example.starter.roaming.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 挂账核销结果；同一挂账只允许核销一次。
 */
public record WriteOffResponse(
        String suspenseNo,
        String status,
        BigDecimal energyDeltaKwh,
        BigDecimal receivableDelta,
        BigDecimal payableDelta,
        String operatorName,
        Instant writtenOffAt,
        boolean replayed) {
}
