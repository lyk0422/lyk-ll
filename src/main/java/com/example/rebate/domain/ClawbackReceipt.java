package com.example.rebate.domain;

import java.time.Instant;

/**
 * 追缴回执：独立流水号，可多笔部分到账。
 *
 * @param detailId 所属追缴明细
 * @param orderId 所属冲回单（冗余便于查询与校验）
 * @param receiptSerial 追缴回执独立流水号（全局唯一）
 * @param receivedCents 本笔实际收回金额（正数整数分）
 * @param receivedAt 回执业务时刻（UTC，精确时刻，来自请求）
 */
public record ClawbackReceipt(
        Long id,
        Long detailId,
        Long orderId,
        String receiptSerial,
        long receivedCents,
        Instant receivedAt,
        Instant createdAt) {
}
