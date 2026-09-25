package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 凭证响应。
 */
public record VoucherResponse(
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
        Instant createdAt) {
}
