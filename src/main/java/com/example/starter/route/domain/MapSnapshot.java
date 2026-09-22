package com.example.starter.route.domain;

import java.util.List;
import java.util.Map;

/**
 * 某个地图版本的不可变快照。发布后其节点与路段集合不再变化；
 * 热更新通过发布新版本实现，废弃只改变版本状态。
 */
public record MapSnapshot(
        String version,
        MapVersionStatus status,
        Map<String, Node> nodes,
        List<Segment> segments) {

    /**
     * 端点（节点），坐标单位米。
     */
    public record Node(String nodeId, double x, double y) {
    }

    /**
     * 有向路段：from 指向 to。
     */
    public record Segment(
            String segmentId,
            String fromNodeId,
            String toNodeId,
            double lengthM,
            boolean valid) {
    }
}
