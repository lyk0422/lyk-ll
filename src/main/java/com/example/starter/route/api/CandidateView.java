package com.example.starter.route.api;

import java.util.List;

/**
 * 单个候选路段的评分明细与排序、拒绝原因。
 */
public record CandidateView(
        String endpoint,
        int rank,
        String segmentId,
        double distanceM,
        double headingDeltaDeg,
        double distanceScore,
        double headingScore,
        double connectivityScore,
        double totalScore,
        boolean selected,
        List<String> rejectReasons) {
}
