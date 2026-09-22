package com.example.starter.perception.api;

import java.time.Instant;
import java.util.Map;

/**
 * 轨迹快照：未指定时刻时来自当前表，指定时刻时从 H2 事件流重放重建。
 */
public record TrackSnapshotResponse(
        String trackId,
        String category,
        String state,
        double confidence,
        double x,
        double y,
        Instant lastObservedAt,
        long version,
        Map<String, Object> evidence,
        Instant createdAt,
        /** LIVE=当前表读取；REPLAYED=截至指定时刻从事件流重建。 */
        String source,
        Instant snapshotAt,
        Instant reconstructedAt
) {
}
