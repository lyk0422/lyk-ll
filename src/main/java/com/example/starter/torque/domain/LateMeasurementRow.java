package com.example.starter.torque.domain;

import java.time.Instant;

/** late_measurement 表行记录（归档后迟到测量事件）。 */
public record LateMeasurementRow(
        long id,
        long cardId,
        String pointCode,
        java.math.BigDecimal measuredTorque,
        String toolNo,
        String operator,
        Instant recordedAt,
        Instant receivedAt
) {
}
