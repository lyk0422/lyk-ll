package com.example.starter.ventilation.domain;

import com.example.starter.ventilation.ModeStatus;

/**
 * 火灾模式会话行，对应 mode_session 表。
 */
public class ModeSessionRow {

    private long id;
    private String zoneCode;
    private String activateRequestId;
    private String smokeDirection;
    private String targetCondition;
    private ModeStatus status;
    private boolean fault;
    private long activatedAt;
    private Long resetCompletedAt;
    private Long archivedAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getZoneCode() {
        return zoneCode;
    }

    public void setZoneCode(String zoneCode) {
        this.zoneCode = zoneCode;
    }

    public String getActivateRequestId() {
        return activateRequestId;
    }

    public void setActivateRequestId(String activateRequestId) {
        this.activateRequestId = activateRequestId;
    }

    public String getSmokeDirection() {
        return smokeDirection;
    }

    public void setSmokeDirection(String smokeDirection) {
        this.smokeDirection = smokeDirection;
    }

    public String getTargetCondition() {
        return targetCondition;
    }

    public void setTargetCondition(String targetCondition) {
        this.targetCondition = targetCondition;
    }

    public ModeStatus getStatus() {
        return status;
    }

    public void setStatus(ModeStatus status) {
        this.status = status;
    }

    public boolean isFault() {
        return fault;
    }

    public void setFault(boolean fault) {
        this.fault = fault;
    }

    public long getActivatedAt() {
        return activatedAt;
    }

    public void setActivatedAt(long activatedAt) {
        this.activatedAt = activatedAt;
    }

    public Long getResetCompletedAt() {
        return resetCompletedAt;
    }

    public void setResetCompletedAt(Long resetCompletedAt) {
        this.resetCompletedAt = resetCompletedAt;
    }

    public Long getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Long archivedAt) {
        this.archivedAt = archivedAt;
    }
}
