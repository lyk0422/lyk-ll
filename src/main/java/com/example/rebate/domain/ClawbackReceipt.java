package com.example.rebate.domain;

import java.time.Instant;

/**
 * 追缴到账回执：独立流水号全局唯一，允许多笔部分到账。
 *
 * @param reversalId 所属（已批准）冲回单 id
 * @param receiptSerial 追缴回执独立流水号；同流水异金额拒绝，同流水同金额去重
 * @param amountCents 本笔实际收回金额（正数整数分）
 * @param receivedAt 到账登记时刻（UTC）
 */
public record ClawbackReceipt(
        Long id,
        Long reversalId,
        String receiptSerial,
        long amountCents,
        Instant receivedAt) {
}
