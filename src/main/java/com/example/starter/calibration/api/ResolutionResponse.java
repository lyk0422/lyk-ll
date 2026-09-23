package com.example.starter.calibration.api;

import com.example.starter.calibration.domain.CalibrationStatus;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 解析结果：返回选中版本与可追溯 {@code resolutionId}。
 */
public record ResolutionResponse(
        String resolutionId,
        String paramKey,
        String vehicleId,
        String sensorId,
        String mapVersion,
        Instant resolvedAt,
        long selectedVersionId,
        String selectedVersion,
        CalibrationStatus status,
        JsonNode content,
        String canaryReleaseId) {
}
