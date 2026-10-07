package com.example.starter.cosmetic.domain;

import java.time.Instant;

/**
 * 标签领用凭据（持久化对象的内存镜像，字段口径与 label_draw 表一致）。
 *
 * <p>每包装批可多次领用，逐笔凭据可追溯；备案换版/注销后已领凭据只追加
 * 风险关联（riskFlag/riskNote/riskAtUtc），不抹历史。所有时刻字段均为 UTC。</p>
 */
public record LabelDrawRecord(
        Long id,
        String printRunNo,
        String batchNo,
        String productCode,
        int quantity,
        String requestId,
        Instant drawnAtUtc,
        boolean riskFlag,
        String riskNote,
        Instant riskAtUtc
) {
}
