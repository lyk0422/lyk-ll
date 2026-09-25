package com.example.starter.persistence;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 计量读数持久化对象。
 */
public record MeterReadingPO(
        Long id,
        String stationCode,
        String meterId,
        Instant readingTime,
        BigDecimal meterIndex,
        BigDecimal heatingValue,
        Instant receivedAt,
        boolean late) {
}
