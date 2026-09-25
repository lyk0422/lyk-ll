package com.example.rebate.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次状态视图。
 */
public record BatchView(
        String batchNo,
        String status,
        String declareDate,
        BigDecimal totalDeclaredTax,
        Instant frozenAt,
        Instant voidedAt,
        Instant closedAt) {
}
