package com.example.starter.gas.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 气质采样记录表行（quality_sample）。
 */
public record SampleRow(
        Long id,
        String requestId,
        String verificationNo,
        String stationCode,
        Instant sampleTime,
        LocalDate gasDate,
        BigDecimal sampleVolume,
        String compositionJson,
        BigDecimal sampleHeatingValue,
        Instant createdAt) {
}
