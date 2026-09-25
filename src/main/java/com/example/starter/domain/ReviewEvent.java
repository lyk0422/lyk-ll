package com.example.starter.domain;

import java.time.Instant;

/**
 * 审查事件流水 PO（含迟到资料事件，可随审查链复算）。
 */
public class ReviewEvent {

    private Long id;
    private Long reviewId;
    private String eventType;
    private String payload;
    private Instant eventAt;
    private Instant createdAt;

    public ReviewEvent() {
    }

    public ReviewEvent(Long reviewId, String eventType, String payload, Instant eventAt) {
        this.reviewId = reviewId;
        this.eventType = eventType;
        this.payload = payload;
        this.eventAt = eventAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getReviewId() {
        return reviewId;
    }

    public void setReviewId(Long reviewId) {
        this.reviewId = reviewId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Instant getEventAt() {
        return eventAt;
    }

    public void setEventAt(Instant eventAt) {
        this.eventAt = eventAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
