package com.example.starter.fishery.db;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 年度结转台账行：{@code amountKg} 为结转额度（千克，不超过来源年度核定配额的 20%），
 * {@code usedKg} 为次年优先核销已扣减量（千克）。
 */
public record CarryoverRow(
        Long id,
        String vesselCode,
        String speciesCode,
        int fromYear,
        int toYear,
        BigDecimal amountKg,
        BigDecimal usedKg,
        Instant createdAt) {
}
