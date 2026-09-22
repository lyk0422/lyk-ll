package com.example.starter.route.repo;

/**
 * route_candidate 行记录；角度单位度，距离单位米。
 *
 * @param rejectReasons 拒绝原因码逗号分隔，空串表示未拒绝
 */
public record RouteCandidateRow(
        long id,
        String requestId,
        String endpoint,
        int rankNo,
        String segmentId,
        double distanceM,
        double headingDeltaDeg,
        double distanceScore,
        double headingScore,
        double connectivityScore,
        double totalScore,
        boolean selected,
        String rejectReasons) {
}
