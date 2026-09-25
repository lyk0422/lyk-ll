package com.example.starter.gas.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 交接凭证表行（custody_voucher）。
 */
public record VoucherRow(
        Long id,
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

    public static final String TYPE_ORIGINAL = "ORIGINAL";
    public static final String TYPE_ADJUSTMENT = "ADJUSTMENT";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PARTIALLY_CONFIRMED = "PARTIALLY_CONFIRMED";
    public static final String STATUS_EFFECTIVE = "EFFECTIVE";
    public static final String STATUS_DISPUTED = "DISPUTED";
    public static final String STATUS_SEALED = "SEALED";
}
