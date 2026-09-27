package com.example.rebate.domain;

import java.time.Instant;

/**
 * 冲回单。
 *
 * @param correctionNo 唯一外部更正编号
 * @param amountCents 申请冲回金额（正整数分）
 * @param submittedBy 提交人
 * @param reviewedBy 复核人（批准/驳回时必填，且不同于提交人）
 * @param approvedCents 批准金额（整数分），批准时等于申请额；驳回/待复核为 0
 * @param collectedCents 追缴实际到账累计（整数分）
 * @param versionNo 乐观版本号，从 1 开始，每次成功变更加 1
 */
public record Reversal(
        Long id,
        Long batchId,
        String declarationNo,
        String correctionNo,
        String reason,
        Long amountCents,
        ReversalStatus status,
        String submittedBy,
        Instant submittedAt,
        String reviewedBy,
        Instant reviewedAt,
        String rejectReason,
        Long approvedCents,
        Long collectedCents,
        Instant completedAt,
        Integer versionNo,
        Instant createdAt,
        Instant updatedAt) {
}
