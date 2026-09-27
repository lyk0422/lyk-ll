package com.example.rebate.domain;

import java.time.Instant;

/**
 * 追缴明细：冲回批准的同一事务内产生一单，独立于原退库核销流水。
 *
 * @param clawbackId 所属冲回单 ID
 * @param approvedAmountCents 批准追缴金额，单位：分，等于冲回单金额
 * @param receivedAmountCents 累计实际收回，单位：分
 * @param status RECOVERING / COMPLETED
 */
public record ClawbackRecovery(
        Long id,
        long clawbackId,
        long approvedAmountCents,
        long receivedAmountCents,
        RecoveryStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
