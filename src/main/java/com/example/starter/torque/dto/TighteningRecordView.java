package com.example.starter.torque.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 力矩曲线复算视图：紧固记录链中的一次测量（只追加，不覆盖）。
 */
public record TighteningRecordView(
        String pointCode,
        int attemptNo,
        BigDecimal measuredTorque,
        BigDecimal nominalTorque,
        boolean withinTolerance,
        String toolNo,
        String operator,
        Instant recordedAt,
        boolean late
) {
}
