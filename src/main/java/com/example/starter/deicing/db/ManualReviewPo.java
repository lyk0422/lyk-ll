package com.example.starter.deicing.db;

import java.time.Instant;

/**
 * 人工确认队列 manual_review_queue 的 PO：库存不足的两步法申请转入。
 */
public class ManualReviewPo {

    private long id;
    /** 关联的申请 requestId，唯一。 */
    private String requestId;
    private String flightNumber;
    /** 转人工成因，如 INSUFFICIENT_FLUID。 */
    private String reason;
    private String detail;
    /** 状态：PENDING 待人工确认。 */
    private String status;
    private Instant createdAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getFlightNumber() {
        return flightNumber;
    }

    public void setFlightNumber(String flightNumber) {
        this.flightNumber = flightNumber;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
