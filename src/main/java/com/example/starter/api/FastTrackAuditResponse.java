package com.example.starter.api;

import com.example.starter.repo.FastTrackAuditRow;

import java.time.Instant;

/**
 * 快速通道结论留痕响应。decision：AUTO_APPROVED 快速放行 / CONFLICT_WIN 冲突优先 /
 * CONFLICT_YIELD 冲突让路 / RESUMED 恢复执行。
 */
public record FastTrackAuditResponse(
        Long id,
        long planId,
        String decision,
        String detail,
        Instant createdAt
) {
    public static FastTrackAuditResponse of(FastTrackAuditRow row) {
        return new FastTrackAuditResponse(row.id(), row.planId(), row.decision(), row.detail(), row.createdAt());
    }
}
