package com.example.starter.fishery.api.dto;

import java.math.BigDecimal;

/**
 * 配额台账视图（可复算）；结转额度在次年台账单列：
 * {@code carryoverKg} 为上年结转额度（千克），{@code carryoverUsedKg} 为结转额度已核销量（千克）。
 */
public record QuotaView(
        String vesselCode,
        String speciesCode,
        int year,
        BigDecimal quotaKg,
        BigDecimal usedKg,
        BigDecimal remainingKg,
        BigDecimal carryoverKg,
        BigDecimal carryoverUsedKg) {
}
