package com.example.starter.costmap.web.dto;

/**
 * 地图元数据响应。
 */
public record MapResponse(
        String mapId,
        int width,
        int height,
        double resolution,
        double originX,
        double originY,
        double inscribedRadius,
        double inflationRadius,
        double costScalingFactor,
        long liveSeq,
        long allocSeq,
        long snapshotCount,
        long createdAt) {
}
