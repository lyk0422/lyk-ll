package com.example.rebate.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 追缴明细视图：含批准额、累计收回与全部回执。金额单位均为分。
 */
public record RecoveryView(
        Long recoveryId,
        Long clawbackId,
        String status,
        long approvedAmountCents,
        long receivedAmountCents,
        long remainingCents,
        int clawbackVersion,
        List<ReceiptView> receipts) {

    /**
     * 单笔追缴回执视图。
     */
    public record ReceiptView(
            Long id,
            String serialNo,
            long receivedAmountCents,
            Instant receivedAt) {
    }
}
