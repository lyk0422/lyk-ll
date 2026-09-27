package com.example.starter.domain;

import java.time.Instant;

/**
 * 通关单事件流水 PO（登记、绑定、核销、释放、撤销、修订、风险事件）。
 */
public class CustomsEvent {

    private Long id;
    private Long declarationId;
    private String eventType;
    private String payload;
    private Instant eventAt;
    private Instant createdAt;

    public CustomsEvent() {
    }

    public CustomsEvent(Long declarationId, String eventType, String payload, Instant eventAt) {
        this.declarationId = declarationId;
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

    public Long getDeclarationId() {
        return declarationId;
    }

    public void setDeclarationId(Long declarationId) {
        this.declarationId = declarationId;
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
