package com.example.starter.route.matcher;

import java.util.List;

/**
 * 一次匹配计算的完整结果：候选明细、确定性选择与连通路径，或稳定错误码。
 * 成功时 errorCode 为 null 且 pathSegmentIds 非空；失败时 selectedStart/End 为 null。
 */
public record MatchOutcome(
        boolean matched,
        String errorCode,
        List<ScoredCandidate> startCandidates,
        List<ScoredCandidate> endCandidates,
        String selectedStartSegmentId,
        String selectedEndSegmentId,
        List<String> pathNodeIds,
        List<String> pathSegmentIds,
        double totalScore) {
}
