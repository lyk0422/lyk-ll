package com.example.starter.wb.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 关闭后迟到的值机事件行（late_event）。
 */
public record LateEventRow(
        Long id,
        long flightId,
        long manifestId,
        int passengerCount,
        BigDecimal weightKg,
        String payloadJson,
        boolean alerted,
        LocalDateTime createdAt
) {
}
