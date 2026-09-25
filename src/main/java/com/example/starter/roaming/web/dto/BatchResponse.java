package com.example.starter.roaming.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 清分单（批）响应；封存为终态后重复封存返回本单且不重新计算。
 */
public record BatchResponse(
        String requestId,
        String batchNo,
        LocalDate settlementDay,
        String status,
        int sessionCount,
        long totalEnergyWh,
        BigDecimal totalReceivable,
        BigDecimal totalPayable,
        Instant frozenAt,
        Instant sealedAt,
        List<ClearingDetailDto> details) {
}
