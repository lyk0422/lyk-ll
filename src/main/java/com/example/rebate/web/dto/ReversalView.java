package com.example.rebate.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 冲回单视图（含追缴明细流水号与全部到账回执）。
 *
 * @param versionNo 当前乐观版本，从 1 开始，每次成功变更 +1
 * @param receivedCents 已到账累计（整数分）
 * @param receivableRemainingCents 批准金额剩余待收回（整数分），恰 0 时 status=COMPLETED
 * @param clawbackSerial 批准时产生的独立追缴流水号，未批准为 {@code null}
 * @param remainingAvailableCents 本次响应时刻该报关单剩余可冲额（整数分，冻结-已占用）；
 *                                幂等重放返回首次冻结结果，不随后续余额变化重算
 * @param ledgerVersion 本次响应时刻台账版本（整数）
 */
public record ReversalView(
        long id,
        String batchNo,
        String declarationNo,
        String correctionNo,
        String reason,
        long amountCents,
        String status,
        String submitter,
        String reviewer,
        long receivedCents,
        long receivableRemainingCents,
        int versionNo,
        String clawbackSerial,
        Long remainingAvailableCents,
        Integer ledgerVersion,
        Instant submittedAt,
        Instant reviewedAt,
        List<ReceiptView> receipts) {

    /**
     * 单笔追缴回执视图。
     */
    public record ReceiptView(
            long id,
            String receiptSerial,
            long amountCents,
            Instant receivedAt) {
    }
}
