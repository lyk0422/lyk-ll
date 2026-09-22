package com.example.starter.route.repo;

/**
 * map_segment 行记录（有向路段，长度单位米）。
 */
public record MapSegmentRow(
        long id,
        String version,
        String segmentId,
        String fromNodeId,
        String toNodeId,
        double lengthM,
        boolean valid) {
}
