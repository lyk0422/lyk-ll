package com.example.starter.schedule.domain;

import com.example.starter.schedule.model.EntryStatus;

import java.time.Instant;

/**
 * 编排条目：一条申请成功后在某版本内占用同站一个半开时间区间。
 */
public class ScheduleEntry {

    private Long id;
    private String requestId;
    private long satelliteId;
    private long stationId;
    private long windowId;
    private long versionId;
    private int priority;
    private EntryStatus status;
    private Instant startTime;
    private Instant endTime;

    /** 被挤占时指向抢占方条目；空值表示未发生抢占。 */
    private Long preemptedByEntryId;

    /** 重排建议窗口；空值表示暂无可用替代窗口。 */
    private Long suggestionWindowId;
    private Instant suggestionStartTime;
    private Instant suggestionEndTime;

    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
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

    public long getVersionId() {
        return versionId;
    }

    public void setVersionId(long versionId) {
        this.versionId = versionId;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public EntryStatus getStatus() {
        return status;
    }

    public void setStatus(EntryStatus status) {
        this.status = status;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public Long getPreemptedByEntryId() {
        return preemptedByEntryId;
    }

    public void setPreemptedByEntryId(Long preemptedByEntryId) {
        this.preemptedByEntryId = preemptedByEntryId;
    }

    public Long getSuggestionWindowId() {
        return suggestionWindowId;
    }

    public void setSuggestionWindowId(Long suggestionWindowId) {
        this.suggestionWindowId = suggestionWindowId;
    }

    public Instant getSuggestionStartTime() {
        return suggestionStartTime;
    }

    public void setSuggestionStartTime(Instant suggestionStartTime) {
        this.suggestionStartTime = suggestionStartTime;
    }

    public Instant getSuggestionEndTime() {
        return suggestionEndTime;
    }

    public void setSuggestionEndTime(Instant suggestionEndTime) {
        this.suggestionEndTime = suggestionEndTime;
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
