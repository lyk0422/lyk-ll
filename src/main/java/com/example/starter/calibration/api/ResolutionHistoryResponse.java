package com.example.starter.calibration.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 历史解析记录视图：按 resolutionId 查询当时使用的参数版本。
 * 后续发布/回滚不会改变这里返回的版本引用与内容。
 */
public record ResolutionHistoryResponse(
        String resolutionId,
        String paramKey,
        String vehicleId,
        String sensorId,
        String mapVersion,
        Instant resolvedAt,
        long selectedVersionId,
        String selectedVersion,
        JsonNode content,
        String canaryReleaseId) {
}
