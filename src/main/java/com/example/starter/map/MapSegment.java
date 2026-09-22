package com.example.starter.map;

/**
 * 地图路段领域对象：端点拓扑 + 直线几何 + 有效状态。
 *
 * @param segmentId 路段业务标识，版本内唯一
 * @param startNode 起点拓扑节点标识
 * @param endNode   终点拓扑节点标识
 * @param startX    起点坐标 X，单位米
 * @param startY    起点坐标 Y，单位米
 * @param endX      终点坐标 X，单位米
 * @param endY      终点坐标 Y，单位米
 * @param active    有效状态；false 表示已失效，不参与候选匹配
 */
public record MapSegment(
        String segmentId,
        String startNode,
        String endNode,
        double startX,
        double startY,
        double endX,
        double endY,
        boolean active) {

    /**
     * 路段航向角，单位度，范围 [0,360)。
     */
    public double headingDegrees() {
        double deg = Math.toDegrees(Math.atan2(endY - startY, endX - startX));
        return (deg + 360.0) % 360.0;
    }
}
