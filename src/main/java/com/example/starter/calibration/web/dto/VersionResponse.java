package com.example.starter.calibration.web.dto;

import java.time.Instant;
import java.util.List;

import com.example.starter.calibration.domain.CalibrationStatus;
import com.example.starter.calibration.domain.CalibrationVersion;
import com.example.starter.calibration.domain.SensorType;
import com.example.starter.calibration.domain.VehicleScope;

/**
 * 标定版本响应。
 */
public record VersionResponse(
        Long id,
        String key,
        SensorType sensorType,
        String version,
        String contentDigest,
        String payload,
        CalibrationStatus status,
        Instant effectiveFrom,
        Instant effectiveTo,
        List<String> vehicles,
        boolean allVehicles,
        String sensorId,
        Integer mapVersionFrom,
        Integer mapVersionTo,
        String validationError,
        String createdBy,
        Instant createdAt,
        String approvedBy,
        Instant approvedAt,
        String activatedBy,
        Instant activatedAt,
        String rolledBackBy,
        Instant rolledBackAt,
        Long canaryReleaseId) {

    public static VersionResponse from(CalibrationVersion v) {
        boolean all = v.getVehicleScope() instanceof VehicleScope.All;
        List<String> vehicles = all ? List.of()
                : ((VehicleScope.Vehicles) v.getVehicleScope()).vehicleIds();
        return new VersionResponse(
                v.getId(), v.getCalibrationKey(), v.getSensorType(), v.getVersion(),
                v.getContentDigest(), v.getPayload(), v.getStatus(),
                v.getEffectiveFrom(), v.getEffectiveTo(),
                vehicles, all, v.getSensorId(),
                v.getMapVersionFrom(), v.getMapVersionTo(), v.getValidationError(),
                v.getCreatedBy(), v.getCreatedAt(),
                v.getApprovedBy(), v.getApprovedAt(),
                v.getActivatedBy(), v.getActivatedAt(),
                v.getRolledBackBy(), v.getRolledBackAt(),
                v.getCanaryReleaseId());
    }
}
