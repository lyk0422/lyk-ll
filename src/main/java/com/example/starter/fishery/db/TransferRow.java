package com.example.starter.fishery.db;

import com.example.starter.fishery.domain.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 跨船配额调剂单行：{@code amountKg} 为调剂量（千克），{@code expiresAt} 为确认截止时刻
 * （UTC，发起后 48 小时），{@code effectiveAt} 未生效为空。
 */
public record TransferRow(
        Long id,
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
        int version) {
}
