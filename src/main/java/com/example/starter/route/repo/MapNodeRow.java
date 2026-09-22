package com.example.starter.route.repo;

/**
 * map_node 行记录，单位米。
 */
public record MapNodeRow(
        long id,
        String version,
        String nodeId,
        double x,
        double y) {
}
