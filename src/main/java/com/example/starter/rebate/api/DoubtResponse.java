package com.example.starter.rebate.api;

import java.time.Instant;

/**
 * 疑点信息。
 *
 * @param id          疑点 ID
 * @param batchId     批次 ID
 * @param docId       来源单证 ID
 * @param status      OPEN / EXPLAINED / CLEARED
 * @param explanation 补充说明
 * @param createdAt   创建时间（UTC）
 * @param explainedAt 说明时间（UTC）
 * @param reviewedAt  复核时间（UTC）
 */
public record DoubtResponse(
        Long id,
        Long batchId,
        Long docId,
        String status,
        String explanation,
        Instant createdAt,
        Instant explainedAt,
        Instant reviewedAt
) {
}
