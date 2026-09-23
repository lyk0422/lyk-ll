package com.example.starter.calibration.api;

import com.example.starter.calibration.domain.CalibrationKind;
import com.example.starter.calibration.domain.ParamStatus;
import com.example.starter.calibration.domain.SensorType;

import java.time.Instant;
import java.util.List;

/**
 * 参数版本视图。
 */
public record ParamView(
        Long id,
        String paramKey,
        CalibrationKind kind,
        SensorType sensorType,
        String version,
        String contentDigest,
        ParamStatus status,
        Instant effectiveFrom,
        Instant effectiveTo,
        List<String> vehicleScope,
        List<String> sensorScope,
        String mapVersionMin,
        String mapVersionMax,
        String createdBy,
        Instant createdAt,
        String approvedBy,
        Instant approvedAt
) {
}
