package com.example.starter.gas.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 计量读数表行（meter_reading）。
 */
public record MeterReadingRow(
        Long id,
        String requestId,
        String stationCode,
        Instant meterTime,
        LocalDate gasDate,
        BigDecimal volume,
        BigDecimal heatingValue,
        Instant createdAt) {
}
