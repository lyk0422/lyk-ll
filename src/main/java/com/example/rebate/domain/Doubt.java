package com.example.rebate.domain;

import java.time.Instant;

/**
 * 疑点记录。
 *
 * @param status OPEN / EXPLAINED / RESOLVED
 * @param explanation 企业补充说明，{@code null} 尚未提交
 */
public record Doubt(
        Long id,
        Long batchId,
        Long checkDetailId,
        String doubtType,
        DoubtStatus status,
        String explanation,
        Instant explainedAt,
        Instant reviewedAt) {
}
