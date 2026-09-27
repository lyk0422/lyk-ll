package com.example.starter.domain;

import java.time.Instant;

/**
 * 通关单风险事件流水 PO。撤销通关单、释放预占、阻断签发等均留痕；证据一经写入不可变。
 */
public class CustomsRiskEvent {

    private Long id;
    private Long customsId;
    /** 关联子批审查流程 ID；事件不针对具体子批（如通关单登记/撤销本身）时为空。 */
    private Long reviewId;
    private String eventType;
    private String payload;
    private Instant eventAt;
    private Instant createdAt;

    public CustomsRiskEvent() {
    }

    public CustomsRiskEvent(Long customsId, Long reviewId, String eventType, String payload, Instant eventAt) {
        this.customsId = customsId;
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

    public Long getCustomsId() {
        return customsId;
    }

    public void setCustomsId(Long customsId) {
        this.customsId = customsId;
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
