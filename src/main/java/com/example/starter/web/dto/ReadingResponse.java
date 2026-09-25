package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 计量读数响应（按表底时刻升序归位）。
 */
public record ReadingResponse(
        Long id,
        String stationCode,
        String meterId,
        Instant readingTime,
        BigDecimal meterIndex,
        BigDecimal heatingValue,
        Instant receivedAt,
        boolean late) {
}
