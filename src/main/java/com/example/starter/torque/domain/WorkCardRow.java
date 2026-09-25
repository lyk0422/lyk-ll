package com.example.starter.torque.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** work_card 表行记录。 */
public record WorkCardRow(
        long id,
        String cardNo,
        String station,
        String productNo,
        String status,
        Instant dispatchedAt,
        Instant releasedAt,
        Instant quarantinedAt,
        int currentRework
) {
}
