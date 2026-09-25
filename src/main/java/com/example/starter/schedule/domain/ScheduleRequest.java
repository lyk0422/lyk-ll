package com.example.starter.schedule.domain;

import com.example.starter.schedule.model.RequestOutcome;

import java.time.Instant;

/**
 * 编排申请的幂等记录。
 */
public class ScheduleRequest {

    private String requestId;
    private String fingerprint;
    private long satelliteId;
    private long stationId;
    private long windowId;
    private int priority;
    private RequestOutcome outcome;
    private Long resultEntryId;

    /** 拒绝时的冲突详情 JSON；已编排时为空。 */
    private String conflictDetail;

    /** 是否已完成首次处理；false 表示并发处理中的占位。 */
    private boolean completed;

    private Instant createdAt;
    private Instant updatedAt;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public long getSatelliteId() {
        return satelliteId;
    }

    public void setSatelliteId(long satelliteId) {
        this.satelliteId = satelliteId;
    }

    public long getStationId() {
        return stationId;
    }

    public void setStationId(long stationId) {
        this.stationId = stationId;
    }

    public long getWindowId() {
        return windowId;
    }

    public void setWindowId(long windowId) {
        this.windowId = windowId;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public RequestOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(RequestOutcome outcome) {
        this.outcome = outcome;
    }

    public Long getResultEntryId() {
        return resultEntryId;
    }

    public void setResultEntryId(Long resultEntryId) {
        this.resultEntryId = resultEntryId;
    }

    public String getConflictDetail() {
        return conflictDetail;
    }

    public void setConflictDetail(String conflictDetail) {
        this.conflictDetail = conflictDetail;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
