package com.example.starter.calibration.api;

import com.example.starter.calibration.domain.CalibrationStatus;
import com.example.starter.calibration.domain.ParamKind;
import com.example.starter.calibration.domain.SensorType;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 标定版本视图。
 */
public record CalibrationResponse(
        long id,
        String paramKey,
        SensorType sensorType,
        ParamKind kind,
        String version,
        String contentSha256,
        JsonNode content,
        CalibrationStatus status,
        Instant effectiveFrom,
        Instant effectiveTo,
        String vehicleFrom,
        String vehicleTo,
        String sensorFrom,
        String sensorTo,
        String mapFrom,
        String mapTo,
        String createdBy,
        Instant createdAt,
        String approvedBy,
        Instant approvedAt,
        String activatedBy,
        Instant activatedAt,
        String rolledBackBy,
        Instant rolledBackAt) {
}
