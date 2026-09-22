package com.example.starter.route.api;

import java.time.OffsetDateTime;

/**
 * 地图版本视图。
 *
 * @param status       ACTIVE / DEPRECATED
 * @param nodeCount    端点数
 * @param segmentCount 路段数
 */
public record MapVersionResponse(
        String version,
        String status,
        OffsetDateTime publishedAt,
        OffsetDateTime deprecatedAt,
        int nodeCount,
        int segmentCount) {
}
