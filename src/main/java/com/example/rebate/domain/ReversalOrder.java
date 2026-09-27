package com.example.rebate.domain;

import java.time.Instant;

/**
 * 分批冲回单。
 *
 * @param correctionNo 外部更正编号，同批次同编号最多一单
 * @param amountCents 申请冲回金额（正数整数分）
 * @param status PENDING/APPROVED/REJECTED/COMPLETED
 * @param receivedCents 追缴回执累计到账（整数分），不超批准金额
 * @param versionNo 乐观版本，从 1 开始，每次成功变更 +1；写操作须携带 expectedVersion
 * @param reviewedAt 复核时刻（批准/驳回），{@code null} 未复核
 */
public record ReversalOrder(
        Long id,
        Long batchId,
        String declarationNo,
        String correctionNo,
        String reason,
        long amountCents,
        ReversalStatus status,
        String submitter,
        String reviewer,
        long receivedCents,
        int versionNo,
        Instant submittedAt,
        Instant reviewedAt,
        Instant createdAt,
        Instant updatedAt) {
}
