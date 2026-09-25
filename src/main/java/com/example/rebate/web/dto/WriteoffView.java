package com.example.rebate.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 退库核销视图。
 */
public record WriteoffView(
        Long id,
        String batchNo,
        String treasurySerial,
        BigDecimal declaredTax,
        BigDecimal receivedAmount,
        BigDecimal diffAmount,
        BigDecimal diffRatio,
        boolean consistent,
        boolean manualFlag,
        String batchStatus,
        Instant registeredAt) {
}
