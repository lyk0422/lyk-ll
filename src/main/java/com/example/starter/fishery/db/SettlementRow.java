package com.example.starter.fishery.db;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 配额核销明细行（可复算）；核销与余额扣减同事务完成。
 */
public record SettlementRow(
        Long id,
        String tripCode,
        String vesselCode,
        int year,
        String speciesCode,
        BigDecimal deductedKg,
        Instant createdAt) {
}
