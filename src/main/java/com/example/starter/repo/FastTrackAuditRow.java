package com.example.starter.repo;

import java.time.Instant;

/**
 * 临时计划快速通道结论留痕行。decision 取值：AUTO_APPROVED 快速放行 /
 * CONFLICT_WIN 冲突优先 / CONFLICT_YIELD 冲突让路 / RESUMED 恢复执行。
 */
public record FastTrackAuditRow(
        Long id,
        long planId,
        String decision,
        String detail,
        Instant createdAt
) {
}
