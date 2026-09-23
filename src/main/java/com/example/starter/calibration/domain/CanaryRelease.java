package com.example.starter.calibration.domain;

import java.time.Instant;

/**
 * 灰度发布单行对象，对应 canary_release 表。
 */
public class CanaryRelease {

    private Long id;
    private String calibrationKey;
    private Long newVersionId;
    private ReleaseStatus status;
    private String operator;
    private Instant createdAt;
    private String rollbackOperator;
    private Instant rolledBackAt;
    private String reason;

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

    public Long getNewVersionId() {
        return newVersionId;
    }

    public void setNewVersionId(Long newVersionId) {
        this.newVersionId = newVersionId;
    }

    public ReleaseStatus getStatus() {
        return status;
    }

    public void setStatus(ReleaseStatus status) {
        this.status = status;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getRollbackOperator() {
        return rollbackOperator;
    }

    public void setRollbackOperator(String rollbackOperator) {
        this.rollbackOperator = rollbackOperator;
    }

    public Instant getRolledBackAt() {
        return rolledBackAt;
    }

    public void setRolledBackAt(Instant rolledBackAt) {
        this.rolledBackAt = rolledBackAt;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
