package com.example.starter.fishery.db;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 年度结转台账行：{@code quotaYear} 为结转目标年度（次年），{@code carriedKg} 为结转额度（千克），
 * {@code usedKg} 为次年已优先核销的结转额度（千克）。
 */
public record CarryoverRow(
        Long id,
        String vesselCode,
        String speciesCode,
        int quotaYear,
        int sourceYear,
        BigDecimal carriedKg,
        BigDecimal usedKg,
        Instant createdAt) {

    /** 次年剩余可优先核销的结转额度（千克）。 */
    public BigDecimal remainingKg() {
        return carriedKg.subtract(usedKg);
    }
}
