package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 计划快照视图。{@code requests} 为发布时刻的冻结内容，后续状态变化不改写本快照。
 */
public record SnapshotView(
        String snapshotId,
        Instant businessTime,
        Instant publishedAt,
        List<RequestView> requests
) {
}
