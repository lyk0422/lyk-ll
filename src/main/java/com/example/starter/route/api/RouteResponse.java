package com.example.starter.route.api;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 路线请求及其匹配结果视图；同参重放返回该视图的原始内容。
 *
 * @param state          MATCHED / FAILED
 * @param errorCode      失败时的稳定错误码，成功为 null
 * @param boundMapVersion 请求首次提交时固定绑定的地图版本
 * @param startCandidates 起点候选（按总分降序）
 * @param endCandidates   终点候选（按总分降序）
 * @param match           选中路径，失败为 null
 */
public record RouteResponse(
        String requestId,
        String boundMapVersion,
        String state,
        String errorCode,
        OffsetDateTime createdAt,
        List<CandidateView> startCandidates,
        List<CandidateView> endCandidates,
        MatchView match) {
}
