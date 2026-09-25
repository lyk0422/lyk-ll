package com.example.starter.roaming.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 清分批行。
 */
public record BatchRow(
        Long id,
        String batchNo,
        LocalDate settlementDay,
        String status,
        int sessionCount,
        long totalEnergyWh,
        BigDecimal totalReceivable,
        BigDecimal totalPayable,
        LocalDateTime frozenAt,
        LocalDateTime sealedAt) {

    public static final String STATUS_FROZEN = "FROZEN";
    public static final String STATUS_SEALED = "SEALED";
}
