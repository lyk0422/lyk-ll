package com.example.starter.gas.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 气质采样记录表行（quality_sample）。
 */
public record QualitySampleRow(
        Long id,
        String stationCode,
        Instant sampleTime,
        LocalDate gasDate,
        BigDecimal sampleVolume,
        BigDecimal heatingValue,
        String componentsJson,
        String requestId) {
}
