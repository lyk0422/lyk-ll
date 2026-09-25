package com.example.starter.roaming.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 清分单；封存为终态，重复封存返回首次清分单且不重新计算。
 */
public record SettlementBatchResponse(
        Long batchId,
        String batchNo,
        LocalDate settlementDay,
        String status,
        Integer sessionCount,
        BigDecimal totalEnergyKwh,
        BigDecimal totalReceivable,
        BigDecimal totalPayable,
        Instant sealedAt,
        boolean replayed,
        List<Detail> details) {

    /**
     * 清分明细。
     */
    public record Detail(
            String sessionNo,
            Long homeOperatorId,
            Long servingOperatorId,
            BigDecimal energyKwh,
            BigDecimal receivableAmount,
            BigDecimal payableAmount) {
    }
}
