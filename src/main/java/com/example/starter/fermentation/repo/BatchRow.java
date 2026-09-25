package com.example.starter.fermentation.repo;

import com.example.starter.fermentation.BatchStatus;

import java.time.Instant;

/**
 * fermentation_batch 行对象，时间字段均为 UTC 时间戳，null 表示该节点尚未到达。
 */
public record BatchRow(
        long id,
        String batchNo,
        long tankId,
        String tankCode,
        BatchStatus status,
        Instant filledAt,
        Instant primaryStartedAt,
        Instant coolingAt,
        Instant maturingAt,
        Instant judgedAt,
        boolean extensionUsed,
        Integer activeCurveVersion,
        Instant maturityHoldFrom,
        Boolean maturityPassed,
        Instant transferredAt) {
}
