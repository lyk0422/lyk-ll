package com.example.starter.recheck;

import java.time.LocalDateTime;

import com.example.starter.domain.BatchStatus;
import com.example.starter.domain.RecheckResult;

/**
 * 复检响应。retentionUntil 为留样截止时间(UTC)，仅复检不合格转处置时非空；
 * late=true 表示批次已结案，结果仅记录为迟到事件。时间均为 UTC。
 */
public record RecheckResponse(
        Long id,
        long batchId,
        RecheckResult result,
        BatchStatus batchStatus,
        LocalDateTime retentionUntil,
        boolean late,
        LocalDateTime createdAt) {

    public static RecheckResponse from(Recheck recheck, BatchStatus batchStatus, Retention retention) {
        return new RecheckResponse(recheck.id(), recheck.batchId(), recheck.result(), batchStatus,
                retention == null ? null : retention.retainUntil(), false, recheck.createdAt());
    }

    public static RecheckResponse late(long batchId, BatchStatus batchStatus) {
        return new RecheckResponse(null, batchId, null, batchStatus, null, true, null);
    }
}
