package com.example.starter.fishery.db;

import com.example.starter.fishery.domain.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 跨船配额调剂单行：{@code amountKg} 为调剂量（千克），{@code expiresAt} 为确认截止时刻
 * （UTC，发起后 48 小时，恰满未确认自动失效），{@code confirmedAt} 未确认为空。
 */
public record TransferRow(
        Long id,
        String requestId,
        String fromVesselCode,
        String toVesselCode,
        String speciesCode,
        int quotaYear,
        BigDecimal amountKg,
        TransferStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant confirmedAt) {
}
