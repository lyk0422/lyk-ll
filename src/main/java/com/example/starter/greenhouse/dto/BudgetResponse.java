package com.example.starter.greenhouse.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 能耗预算视图。
 */
public record BudgetResponse(
        Long id,
        String zoneCode,
        LocalDate budgetDay,
        BigDecimal budgetKwh,
        BigDecimal remainingKwh) {
}
