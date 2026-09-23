package com.example.starter.calibration.domain;

import java.time.Instant;

/**
 * 解析历史记录行对象，对应 resolution_record 表，只追加不改写。
 */
public class ResolutionRecord {

    private String resolutionId;
    private String calibrationKey;
    private String sensorId;
    private String vehicleId;
    private Integer mapVersion;
    private Instant resolvedTime;
    private Instant resolvedAt;
    private Long selectedVersionId;
    private String selectedVersion;
    private String contentDigest;
    private String payload;
    private Long canaryReleaseId;

    public String getResolutionId() {
        return resolutionId;
    }

    public void setResolutionId(String resolutionId) {
        this.resolutionId = resolutionId;
    }

    public String getCalibrationKey() {
        return calibrationKey;
    }

    public void setCalibrationKey(String calibrationKey) {
        this.calibrationKey = calibrationKey;
    }

    public String getSensorId() {
        return sensorId;
    }

    public void setSensorId(String sensorId) {
        this.sensorId = sensorId;
    }

    public String getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(String vehicleId) {
        this.vehicleId = vehicleId;
    }

    public Integer getMapVersion() {
        return mapVersion;
    }

    public void setMapVersion(Integer mapVersion) {
        this.mapVersion = mapVersion;
    }

    public Instant getResolvedTime() {
        return resolvedTime;
    }

    public void setResolvedTime(Instant resolvedTime) {
        this.resolvedTime = resolvedTime;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public Long getSelectedVersionId() {
        return selectedVersionId;
    }

    public void setSelectedVersionId(Long selectedVersionId) {
        this.selectedVersionId = selectedVersionId;
    }

    public String getSelectedVersion() {
        return selectedVersion;
    }

    public void setSelectedVersion(String selectedVersion) {
        this.selectedVersion = selectedVersion;
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

    public Long getCanaryReleaseId() {
        return canaryReleaseId;
    }

    public void setCanaryReleaseId(Long canaryReleaseId) {
        this.canaryReleaseId = canaryReleaseId;
    }
}
