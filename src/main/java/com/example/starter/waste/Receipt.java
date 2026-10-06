package com.example.starter.waste;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 处理设施回执实体行。
 */
public record Receipt(
        long id,
        long manifestId,
        String requestId,
        String fingerprint,
        BigDecimal receivedNetKg,
        BigDecimal deviationRatio,
        LocalDateTime receivedAt) {
}
