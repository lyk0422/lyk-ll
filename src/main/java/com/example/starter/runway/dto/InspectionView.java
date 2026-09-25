package com.example.starter.runway.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 巡检记录视图。
 */
public record InspectionView(
        long id,
        long closureEventId,
        int inspectorCount,
        int rvrMeters,
        BigDecimal frictionCoefficient,
        String assessment,
        boolean effective,
        boolean late,
        boolean delayed,
        Instant startedAt,
        Instant recordedAt) {
}
