package com.example.rebate.domain;

import java.time.Instant;

/**
 * 冲回单：只引用已结案批次内的一张报关单及冻结退税额。
 *
 * @param externalCorrectionNo 唯一外部更正编号；同批次同编号最多一单
 * @param amountCents 冲回金额，单位：分，正整数
 * @param frozenTaxCents 提交时该报关单冻结退税额快照，单位：分
 * @param status PENDING / APPROVED / REJECTED / COMPLETED
 * @param submittedBy 提交人
 * @param reviewer 复核人，{@code null} 尚未复核
 * @param versionNo 乐观版本号，从 1 开始，每次成功变更加 1
 */
public record Clawback(
        Long id,
        long batchId,
        String batchNo,
        String declarationNo,
        String externalCorrectionNo,
        String reason,
        long amountCents,
        long frozenTaxCents,
        ClawbackStatus status,
        String submittedBy,
        String reviewer,
        Instant reviewedAt,
        int versionNo,
        Instant createdAt,
        Instant updatedAt) {
}
