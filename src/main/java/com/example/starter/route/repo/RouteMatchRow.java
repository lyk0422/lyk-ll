package com.example.starter.route.repo;

/**
 * route_match 行记录；路径节点/路段序列以 JSON 数组字符串保存。
 */
public record RouteMatchRow(
        String requestId,
        String startSegmentId,
        String endSegmentId,
        String pathNodeIds,
        String pathSegmentIds,
        double totalScore) {
}
