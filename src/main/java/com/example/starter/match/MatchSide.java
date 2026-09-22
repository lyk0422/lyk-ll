package com.example.starter.match;

import java.util.List;

/**
 * 一次匹配（起点或终点一侧）的完整解释：全部候选及确定性选择结果。
 *
 * @param candidates         候选评分列表，按 rank 升序
 * @param selectedSegmentId  被选中的候选路段标识
 */
public record MatchSide(
        List<CandidateEvaluation> candidates,
        String selectedSegmentId) {
}
