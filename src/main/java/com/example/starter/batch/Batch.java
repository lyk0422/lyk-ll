package com.example.starter.batch;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.example.starter.domain.BatchStatus;

/**
 * 进场批次（产地报备）。时间均为 UTC；数量单位千克。
 */
public record Batch(
        long id,
        String requestId,
        String fingerprint,
        String origin,
        String variety,
        BigDecimal quantity,
        BatchStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
