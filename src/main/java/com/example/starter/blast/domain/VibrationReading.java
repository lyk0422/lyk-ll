package com.example.starter.blast.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 振动读数持久化对象。
 *
 * @param overLimit 是否超过测点备案振速阈值
 */
public record VibrationReading(
        Long id,
        Long applicationId,
        String zoneCode,
        String pointCode,
        Instant sampleTime,
        BigDecimal velocityMmS,
        boolean overLimit,
        Instant createdAt) {
}
