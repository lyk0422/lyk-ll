package com.example.starter.compliance.repo;

import java.math.BigDecimal;

/**
 * 返工料消费明细行：仅追加不可改；一笔量产对同一料号至多一笔消费。
 */
public record ReworkConsumptionRow(
        Long id,
        Long lotId,
        Long productionRecordId,
        BigDecimal quantityKg,
        long consumedAt) {
}
