package com.example.rebate.domain;

import java.time.Instant;

/**
 * 追缴回执：独立流水号，允许多笔部分到账。
 *
 * @param recoveryId 所属追缴明细 ID
 * @param clawbackId 所属冲回单 ID
 * @param serialNo 追缴回执独立流水号，全局唯一
 * @param receivedAmountCents 本笔实际收回金额，单位：分，正整数
 * @param receivedAt 回执登记的精确时刻(UTC)
 */
public record ClawbackReceipt(
        Long id,
        long recoveryId,
        long clawbackId,
        String serialNo,
        long receivedAmountCents,
        Instant receivedAt,
        Instant createdAt) {
}
