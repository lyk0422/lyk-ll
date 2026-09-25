package com.example.starter.torque.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** 归档后迟到测量事件视图。 */
public record LateMeasurementView(
        String pointCode,
        BigDecimal measuredTorque,
        String toolNo,
        String operator,
        Instant recordedAt,
        Instant receivedAt
) {
}
