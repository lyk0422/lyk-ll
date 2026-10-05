package com.example.starter.gas.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 气质核验单表行（quality_verification）。
 */
public record VerificationRow(
        Long id,
        String verificationNo,
        String requestId,
        String stationCode,
        LocalDate gasDate,
        String voucherNo,
        Integer version,
        String status,
        BigDecimal sampleVolume,
        BigDecimal verifiedHeatingValue,
        BigDecimal voucherHeatingValue,
        BigDecimal deviationRate,
        String conclusion,
        String diffStatus,
        Boolean confirmationPaused,
        Instant createdAt) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    public static final String CONCLUSION_CONSISTENT = "CONSISTENT";
    public static final String CONCLUSION_QUALITY_DIFF = "QUALITY_DIFF";

    public static final String DIFF_NONE = "NONE";
    public static final String DIFF_OPEN = "OPEN";
    public static final String DIFF_RESOLVED = "RESOLVED";
}
