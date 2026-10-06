package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 水质采样行记录。
 */
public record WaterSample(
        long id,
        long zoneId,
        BigDecimal dissolvedOxygen,
        BigDecimal ammoniaNitrogen,
        BigDecimal temperature,
        boolean outOfLimit,
        boolean evaluated,
        LocalDateTime sampledAt,
        String requestId
) {
}
