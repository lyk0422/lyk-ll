package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 排放读数：按 方案+测点+采样时刻 去重。
 */
public record EmissionReading(
        Long id,
        Long planId,
        String kilnId,
        String measurePoint,
        LocalDateTime sampleTime,
        BigDecimal nox,
        BigDecimal so2,
        BigDecimal dust,
        boolean late,
        LocalDateTime receivedAt) {
}
