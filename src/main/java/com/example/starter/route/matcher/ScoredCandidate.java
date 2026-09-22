package com.example.starter.route.matcher;

import java.util.List;

/**
 * 单个候选路段的评分结果。
 *
 * @param rejectReasons 拒绝原因码（如 SEGMENT_INVALID、NOT_CONNECTED），空表示无拒绝
 */
public record ScoredCandidate(
        String segmentId,
        String endpoint,
        double distanceM,
        double headingDeltaDeg,
        double distanceScore,
        double headingScore,
        double connectivityScore,
        double totalScore,
        boolean selected,
        List<String> rejectReasons) {
}
