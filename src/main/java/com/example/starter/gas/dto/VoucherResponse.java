package com.example.starter.gas.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 交接凭证视图。
 */
public record VoucherResponse(
        String voucherNo,
        String requestId,
        String voucherType,
        String originalVoucherNo,
        String stationCode,
        LocalDate gasDate,
        BigDecimal volume,
        BigDecimal weightedHeatingValue,
        BigDecimal energy,
        String status,
        String firstConfirmParty,
        LocalDate effectiveDate,
        Instant effectiveAt,
        Instant sealedAt) {
}
