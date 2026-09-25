package com.example.starter.reinsurance.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 账单视图：明细合计必须等于汇总金额，可逐笔复算。
 */
public record BillResponse(
        String billNo,
        String contractNo,
        String period,
        String billType,
        String refBillNo,
        String status,
        boolean cedantConfirmed,
        boolean reinsurerConfirmed,
        BigDecimal premiumTotal,
        BigDecimal recoveryTotal,
        BigDecimal netTotal,
        Instant createdAt,
        Instant sealedAt,
        List<BillItemResponse> items,
        List<DisputeResponse> disputes) {

    /**
     * 异议明细视图。
     */
    public record DisputeResponse(
            String itemRef,
            BigDecimal expectedAmount,
            String reason,
            Instant createdAt) {
    }
}
