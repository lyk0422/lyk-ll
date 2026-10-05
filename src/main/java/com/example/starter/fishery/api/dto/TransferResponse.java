package com.example.starter.fishery.api.dto;

import com.example.starter.fishery.domain.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 跨船配额调剂单视图/受理结果。
 */
public record TransferResponse(
        Long transferId,
        String fromVessel,
        String toVessel,
        String speciesCode,
        int quotaYear,
        BigDecimal amountKg,
        TransferStatus status,
        boolean fromConfirmed,
        boolean toConfirmed,
        Instant createdAt,
        Instant expiresAt,
        Instant effectiveAt,
        boolean replayed) implements ReplayAware {

    @Override
    public TransferResponse withReplayed(boolean replayed) {
        return new TransferResponse(transferId, fromVessel, toVessel, speciesCode, quotaYear, amountKg,
                status, fromConfirmed, toConfirmed, createdAt, expiresAt, effectiveAt, replayed);
    }
}
