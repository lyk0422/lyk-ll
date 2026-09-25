package com.example.starter.torque.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** tightening_record 表行记录（只追加链）。 */
public record TighteningRow(
        long id,
        long cardId,
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
