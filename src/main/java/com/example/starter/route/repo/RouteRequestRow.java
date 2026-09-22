package com.example.starter.route.repo;

import java.time.OffsetDateTime;

/**
 * route_request 行记录；航向字段单位度。
 */
public record RouteRequestRow(
        long id,
        String requestId,
        String mapVersion,
        String inputHash,
        double startX,
        double startY,
        double startHeading,
        double endX,
        double endY,
        double endHeading,
        String state,
        String errorCode,
        OffsetDateTime createdAt) {
}
