package com.example.starter.costmap.web.dto;

/**
 * 地图版本元数据响应。
 */
public record MapResponse(
        String versionId,
        int width,
        int height,
        double resolution,
        double inflationRadius,
        double costDecay,
        long seq) {
}
