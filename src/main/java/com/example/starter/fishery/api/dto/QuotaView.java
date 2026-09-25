package com.example.starter.fishery.api.dto;

import java.math.BigDecimal;

/**
 * 配额台账视图（可复算）。
 */
public record QuotaView(
        String vesselCode,
        String speciesCode,
        int year,
        BigDecimal quotaKg,
        BigDecimal usedKg,
        BigDecimal remainingKg) {
}
