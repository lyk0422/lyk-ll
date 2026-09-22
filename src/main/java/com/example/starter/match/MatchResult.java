package com.example.starter.match;

import java.util.List;

/**
 * 路线匹配结果快照：起终点两侧的候选解释与最终路径。
 *
 * @param origin        起点侧匹配解释
 * @param destination   终点侧匹配解释
 * @param pathSegmentIds 从起点路段到终点路段的连通路径（含两端），按行驶顺序
 */
public record MatchResult(
        MatchSide origin,
        MatchSide destination,
        List<String> pathSegmentIds) {
}
