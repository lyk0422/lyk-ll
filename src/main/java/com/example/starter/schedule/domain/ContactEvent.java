package com.example.starter.schedule.domain;

import com.example.starter.schedule.model.EventType;

import java.time.Instant;

/**
 * 回报相关事件流水（如漏报后的迟到回报）。
 */
public class ContactEvent {

    private Long id;
    private long stationId;
    private long windowId;
    private EventType eventType;
    private String payload;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public EventType getEventType() {
        return eventType;
    }

    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
