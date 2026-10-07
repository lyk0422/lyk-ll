package com.example.starter.cosmetic.domain;

import java.time.Instant;

/**
 * 标签领用凭据（持久化对象的内存镜像，字段口径与 label_issue 表一致）。
 *
 * <p>每次领用一条凭据，可追溯；备案换版/注销后仅追加风险关联
 * （riskFlag/riskNote/riskAtUtc），不抹历史。</p>
 */
public record LabelIssue(
        Long id,
        String printRunNo,
        String batchNo,
        String productCode,
        int quantity,
        String requestId,
        Instant issuedAtUtc,
        boolean riskFlag,
        String riskNote,
        Instant riskAtUtc
) {
}
