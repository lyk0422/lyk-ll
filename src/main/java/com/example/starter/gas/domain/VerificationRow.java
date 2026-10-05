package com.example.starter.gas.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 气质组分核验单表行（quality_verification）。
 */
public record VerificationRow(
        Long id,
        String verificationNo,
        String requestId,
        String stationCode,
        LocalDate gasDate,
        String voucherNo,
        Integer version,
        Integer sampleCount,
        BigDecimal verifiedHeatingValue,
        BigDecimal voucherHeatingValue,
        BigDecimal relativeDeviation,
        boolean differenceFlag,
        String status,
        Instant signOffAAt,
        Instant signOffBAt,
        Instant resolvedAt,
        Instant createdAt) {

    /** 核验结论：与凭证一致（含恰 1% 边界）。 */
    public static final String STATUS_CONSISTENT = "CONSISTENT";
    /** 核验结论：气质差异待双方签认。 */
    public static final String STATUS_DIFFERENT_OPEN = "DIFFERENT_OPEN";
    /** 核验结论：气质差异已经双方签认解除。 */
    public static final String STATUS_DIFFERENT_RESOLVED = "DIFFERENT_RESOLVED";
}
