package com.example.starter.quarantine.domain;

import java.time.Instant;

/**
 * 落地报告事件行。结案后的迟到事件也记录（accepted=FALSE），但不改变单据状态。
 */
public class LandingReportPo {

    private long id;
    private String requestId;
    private String reportId;
    private int quantity;
    private Instant reportedAt;
    private boolean quarantineFlag;
    private boolean accepted;
    private String note;
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

    public String getReportId() {
        return reportId;
    }

    public void setReportId(String reportId) {
        this.reportId = reportId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public Instant getReportedAt() {
        return reportedAt;
    }

    public void setReportedAt(Instant reportedAt) {
        this.reportedAt = reportedAt;
    }

    public boolean isQuarantineFlag() {
        return quarantineFlag;
    }

    public void setQuarantineFlag(boolean quarantineFlag) {
        this.quarantineFlag = quarantineFlag;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public void setAccepted(boolean accepted) {
        this.accepted = accepted;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
