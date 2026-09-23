package com.example.starter.calibration.domain;

import java.time.Instant;

/**
 * 标定参数版本持久化行对象，对应 calibration_version 表。
 */
public class CalibrationVersion {

    private Long id;
    private String calibrationKey;
    private SensorType sensorType;
    private String version;
    private String contentDigest;
    private String payload;
    private CalibrationStatus status;
    private Instant effectiveFrom;
    private Instant effectiveTo;
    private VehicleScope vehicleScope;
    private String sensorId;
    private Integer mapVersionFrom;
    private Integer mapVersionTo;
    private String validationError;
    private String createdBy;
    private Instant createdAt;
    private String approvedBy;
    private Instant approvedAt;
    private String activatedBy;
    private Instant activatedAt;
    private String rolledBackBy;
    private Instant rolledBackAt;
    private Long canaryReleaseId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCalibrationKey() {
        return calibrationKey;
    }

    public void setCalibrationKey(String calibrationKey) {
        this.calibrationKey = calibrationKey;
    }

    public SensorType getSensorType() {
        return sensorType;
    }

    public void setSensorType(SensorType sensorType) {
        this.sensorType = sensorType;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getContentDigest() {
        return contentDigest;
    }

    public void setContentDigest(String contentDigest) {
        this.contentDigest = contentDigest;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public CalibrationStatus getStatus() {
        return status;
    }

    public void setStatus(CalibrationStatus status) {
        this.status = status;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(Instant effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public Instant getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(Instant effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public VehicleScope getVehicleScope() {
        return vehicleScope;
    }

    public void setVehicleScope(VehicleScope vehicleScope) {
        this.vehicleScope = vehicleScope;
    }

    public String getSensorId() {
        return sensorId;
    }

    public void setSensorId(String sensorId) {
        this.sensorId = sensorId;
    }

    public Integer getMapVersionFrom() {
        return mapVersionFrom;
    }

    public void setMapVersionFrom(Integer mapVersionFrom) {
        this.mapVersionFrom = mapVersionFrom;
    }

    public Integer getMapVersionTo() {
        return mapVersionTo;
    }

    public void setMapVersionTo(Integer mapVersionTo) {
        this.mapVersionTo = mapVersionTo;
    }

    public String getValidationError() {
        return validationError;
    }

    public void setValidationError(String validationError) {
        this.validationError = validationError;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getActivatedBy() {
        return activatedBy;
    }

    public void setActivatedBy(String activatedBy) {
        this.activatedBy = activatedBy;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public void setActivatedAt(Instant activatedAt) {
        this.activatedAt = activatedAt;
    }

    public String getRolledBackBy() {
        return rolledBackBy;
    }

    public void setRolledBackBy(String rolledBackBy) {
        this.rolledBackBy = rolledBackBy;
    }

    public Instant getRolledBackAt() {
        return rolledBackAt;
    }

    public void setRolledBackAt(Instant rolledBackAt) {
        this.rolledBackAt = rolledBackAt;
    }

    public Long getCanaryReleaseId() {
        return canaryReleaseId;
    }

    public void setCanaryReleaseId(Long canaryReleaseId) {
        this.canaryReleaseId = canaryReleaseId;
    }
}
