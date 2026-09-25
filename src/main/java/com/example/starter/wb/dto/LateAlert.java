package com.example.starter.wb.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 关闭后迟到值机事件告警视图。
 */
public record LateAlert(
        long eventId,
        int passengerCount,
        BigDecimal weightKg,
        String detail,
        boolean alerted,
        LocalDateTime createdAt
) {
}
