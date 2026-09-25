package com.example.starter.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 凭证持久化对象，字段含义与 voucher 表列注释一致。
 */
public record VoucherPO(
        Long id,
        String voucherNo,
        String requestId,
        String stationCode,
        LocalDate gasDay,
        String voucherType,
        BigDecimal volume,
        BigDecimal avgHeatingValue,
        BigDecimal energyMj,
        String status,
        boolean partyAConfirmed,
        boolean partyBConfirmed,
        String partyAName,
        String partyBName,
        Instant partyAAt,
        Instant partyBAt,
        Instant effectiveAt,
        LocalDate disputeDeadline,
        Instant sealedAt,
        String originalVoucherNo,
        String reason,
        String fingerprint,
        Instant createdAt,
        Instant updatedAt) {
}
