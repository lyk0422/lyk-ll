package com.example.starter.greenhouse.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 环控指令下发结果。同 requestId 同参重放时原样返回首次结果，不重复扣预算。
 */
public record CommandResponse(
        Long id,
        String zoneCode,
        LocalDate budgetDay,
        String commandType,
        BigDecimal energyKwh,
        BigDecimal remainingKwh,
        Instant issuedAt) {
}
