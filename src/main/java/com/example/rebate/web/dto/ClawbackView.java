package com.example.rebate.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 追缴明细视图，含到账回执明细。
 *
 * @param version 追缴明细版本：批准产生为 1，每笔成功回执加 1
 * @param remainingCents 尚未收回金额（整数分）= 批准 − 已收
 * @param receipts 已登记回执（按登记顺序）
 */
public record ClawbackView(
        Long id,
        String clawbackNo,
        long approvedCents,
        long collectedCents,
        long remainingCents,
        String status,
        int version,
        Instant createdAt,
        Instant completedAt,
        List<ReceiptView> receipts) {

    /**
     * 单笔追缴回执视图。
     */
    public record ReceiptView(
            Long id,
            String receiptSerial,
            long receivedCents,
            Instant receivedAt,
            Instant createdAt) {
    }
}
