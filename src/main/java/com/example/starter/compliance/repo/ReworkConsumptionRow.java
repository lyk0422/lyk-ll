package com.example.starter.compliance.repo;

import java.math.BigDecimal;

/**
 * 返工料消费流水行：历史消费不可改；productionRecordId 为消费该返工料的新量产记录。
 */
public record ReworkConsumptionRow(
        Long id,
        Long materialId,
        Long productionRecordId,
        BigDecimal weightKg,
        Long consumedAt) {
}
