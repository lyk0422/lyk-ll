package com.example.rebate.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 冲回单视图（含逐单余额与追缴回执列表）。
 *
 * @param version 乐观版本号，从 1 开始，每次成功变更加 1
 * @param frozenTaxCents 该报关单冻结退税额（整数分）
 * @param occupiedCents 该报关单已批准累计占用（整数分）
 * @param remainingCents 剩余可冲额（整数分）
 * @param approvedCents 本单批准金额（整数分）
 * @param collectedCents 本单已实际收回累计（整数分）
 * @param receipts 追缴回执明细
 */
public record ReversalView(
        long id,
        String batchNo,
        String declarationNo,
        String correctionNo,
        String reason,
        long amountCents,
        String status,
        String submittedBy,
        Instant submittedAt,
        String reviewedBy,
        Instant reviewedAt,
        String rejectReason,
        long approvedCents,
        long collectedCents,
        Instant completedAt,
        int version,
        long frozenTaxCents,
        long occupiedCents,
        long remainingCents,
        List<ReceiptView> receipts) {
}
