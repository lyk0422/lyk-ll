package com.example.starter.calibration.api;

import java.time.Instant;

/**
 * resolutionId 历史查询视图：永久指向解析当时使用的版本与内容快照。
 */
public record ResolutionView(
        String resolutionId,
        String paramKey,
        String vehicleId,
        String sensorId,
        String mapVersion,
        Instant resolveTime,
        Long resolvedParamId,
        String resolvedVersion,
        String contentDigest,
        Object content
) {
}
