package com.example.starter.route.api;

import java.util.List;

/**
 * 确定性规则选中的连通路径。仅匹配成功时存在。
 */
public record MatchView(
        String startSegmentId,
        String endSegmentId,
        List<String> pathNodeIds,
        List<String> pathSegmentIds,
        double totalScore) {
}
