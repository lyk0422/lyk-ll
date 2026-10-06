package com.example.starter.transition.model;

import java.time.LocalDateTime;

/**
 * 掺配控制事件：失控暂停与复核恢复全程留痕。
 */
public class BlendControlEvent {

    private Long id;
    private String orderNo;
    /** 事件类型：OUT_OF_CONTROL=失控暂停转人工，RESUMED=复核确认恢复。 */
    private String eventType;
    /** 事件发生时生效比例版本号。 */
    private Integer versionNo;
    /** 复核人，仅恢复事件有值。 */
    private String reviewer;
    private String requestId;
    private LocalDateTime eventTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getReviewer() {
        return reviewer;
    }

    public void setReviewer(String reviewer) {
        this.reviewer = reviewer;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public LocalDateTime getEventTime() {
        return eventTime;
    }

    public void setEventTime(LocalDateTime eventTime) {
        this.eventTime = eventTime;
    }
}
