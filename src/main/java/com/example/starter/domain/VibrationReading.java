package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 振动监测回传读数。velocity 单位 cm/s，时间为 UTC。
 */
public record VibrationReading(
        long id,
        long permitId,
        long zoneId,
        String pointCode,
        Instant sampledAt,
        BigDecimal velocity) {
}
