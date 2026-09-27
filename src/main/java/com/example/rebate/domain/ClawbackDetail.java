package com.example.rebate.domain;

import java.time.Instant;

/**
 * 追缴明细：冲回单批准时在同一事务内产生的独立明细，一单一条。
 *
 * @param clawbackNo 追缴明细独立编号
 * @param approvedCents 批准追缴金额（整数分），批准后不随后续余额变化重算
 * @param collectedCents 累计实际收回（整数分），允许多笔部分到账
 * @param status COLLECTING / COMPLETED
 * @param version 乐观版本，从 1 开始，每笔成功回执加 1
 * @param completedAt 收齐时刻，{@code null} 尚未收齐
 */
public record ClawbackDetail(
        Long id,
        Long orderId,
        Long batchId,
        String clawbackNo,
        long approvedCents,
        long collectedCents,
        ClawbackStatus status,
        int version,
        Instant createdAt,
        Instant completedAt) {

    /** 恰收齐转完成（同时版本加 1）。 */
    public ClawbackDetail markCompleted(long newCollected, Instant now) {
        return new ClawbackDetail(id, orderId, batchId, clawbackNo, approvedCents, newCollected,
                ClawbackStatus.COMPLETED, version + 1, createdAt, now);
    }

    /** 部分到账累加（版本加 1）。 */
    public ClawbackDetail withCollected(long newCollected) {
        return new ClawbackDetail(id, orderId, batchId, clawbackNo, approvedCents, newCollected,
                status, version + 1, createdAt, completedAt);
    }
}
