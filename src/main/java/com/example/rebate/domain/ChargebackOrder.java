package com.example.rebate.domain;

import java.time.Instant;

/**
 * 冲回单：只引用已结案批次内一张报关单及其冻结退税额。
 *
 * @param checkDetailId 结案批次内该报关单的关联校验明细（冻结退税额来源），不修改原明细
 * @param correctionNo 唯一外部更正编号（同批次内唯一）
 * @param amountCents 冲回金额（正数整数分）
 * @param status PENDING / APPROVED / REJECTED
 * @param submitter 提交人
 * @param reviewer 复核人（批准/驳回时记录，必须不同于提交人）
 * @param version 乐观版本，从 1 开始，每次成功变更加 1
 */
public record ChargebackOrder(
        Long id,
        Long batchId,
        Long checkDetailId,
        String declarationNo,
        String correctionNo,
        String reason,
        long amountCents,
        ChargebackStatus status,
        String submitter,
        String reviewer,
        int version,
        Instant submittedAt,
        Instant reviewedAt,
        Instant createdAt,
        Instant updatedAt) {

    /** 版本加 1 的状态迁移（批准/驳回）。 */
    public ChargebackOrder reviewed(ChargebackStatus newStatus, String reviewer, Instant now) {
        return new ChargebackOrder(id, batchId, checkDetailId, declarationNo, correctionNo, reason,
                amountCents, newStatus, submitter, reviewer, version + 1, submittedAt, now,
                createdAt, now);
    }
}
