package com.example.rebate.domain;

import java.time.Instant;

/**
 * 追缴回执明细。
 *
 * @param receiptSerial 独立流水号
 * @param amountCents 本笔实际收回金额（正整数分）
 * @param occurredAt 回执载明的到账精确时刻(UTC)
 * @param registeredAt 回执登记时刻(UTC)
 */
public record ReversalReceipt(
        Long id,
        Long reversalId,
        String receiptSerial,
        Long amountCents,
        Instant occurredAt,
        Instant registeredAt) {
}
