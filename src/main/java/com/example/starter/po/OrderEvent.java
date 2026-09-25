package com.example.starter.po;

import com.example.starter.domain.ActorType;
import com.example.starter.domain.EventType;
import java.time.Instant;

/**
 * 工单轨迹事件。
 */
public class OrderEvent {

    private Long id;
    private Long orderId;
    private EventType eventType;
    private Instant eventTime;
    private ActorType actor;
    private String detail;
    private Long relatedOrderId;
    private Instant createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    public Instant getEventTime() {
        return eventTime;
    }

    public void setEventTime(Instant eventTime) {
        this.eventTime = eventTime;
    }

    public ActorType getActor() {
        return actor;
    }

    public void setActor(ActorType actor) {
        this.actor = actor;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Long getRelatedOrderId() {
        return relatedOrderId;
    }

    public void setRelatedOrderId(Long relatedOrderId) {
        this.relatedOrderId = relatedOrderId;
    }

    public Instant getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Instant createTime) {
        this.createTime = createTime;
    }
}
