package com.example.rebate.web.dto;

import java.time.Instant;

/**
 * 冲回单视图。
 *
 * @param version 冲回单乐观版本（提交成功为 1，复核成功为 2）
 * @param frozenCents 该报关单冻结退税额（整数分）
 * @param occupiedCents 该报关单累计已批准占用（整数分）
 * @param remainingCents 剩余可冲额（整数分）
 * @param clawback 批准后产生的追缴明细视图；待复核/驳回为 {@code null}
 */
public record ChargebackView(
        Long id,
        String batchNo,
        String declarationNo,
        Long checkDetailId,
        String correctionNo,
        String reason,
        long amountCents,
        String status,
        String submitter,
        String reviewer,
        int version,
        Instant submittedAt,
        Instant reviewedAt,
        long frozenCents,
        long occupiedCents,
        long remainingCents,
        ClawbackView clawback) {
}
