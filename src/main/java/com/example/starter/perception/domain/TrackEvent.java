package com.example.starter.perception.domain;

import java.time.Instant;

/**
 * 轨迹事件流实体（track_event 表一行），是快照重放的唯一事实来源。
 */
public class TrackEvent {

    /** 自增主键（同时充当事件全序次序）。 */
    private Long id;
    /** 事件唯一ID（幂等键）。 */
    private String eventId;
    /** 所属轨迹业务ID。 */
    private String trackId;
    /** 事件类型。 */
    private String eventType;
    /** 迁移前状态，首条事件为 null。 */
    private TrackState fromState;
    /** 迁移后状态。 */
    private TrackState toState;
    /** 事件应用后的轨迹版本号。 */
    private long version;
    /** 触发事件的检测ID，生命周期事件为 null。 */
    private String detectionId;
    /** 事件完整载荷JSON。 */
    private String payload;
    /** 事件业务时间(UTC)，取检测时戳。 */
    private Instant eventTime;
    /** 事件落库时间(UTC)。 */
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getTrackId() {
        return trackId;
    }

    public void setTrackId(String trackId) {
        this.trackId = trackId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public TrackState getFromState() {
        return fromState;
    }

    public void setFromState(TrackState fromState) {
        this.fromState = fromState;
    }

    public TrackState getToState() {
        return toState;
    }

    public void setToState(TrackState toState) {
        this.toState = toState;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public String getDetectionId() {
        return detectionId;
    }

    public void setDetectionId(String detectionId) {
        this.detectionId = detectionId;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Instant getEventTime() {
        return eventTime;
    }

    public void setEventTime(Instant eventTime) {
        this.eventTime = eventTime;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
