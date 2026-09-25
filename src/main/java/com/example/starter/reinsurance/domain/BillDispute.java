package com.example.starter.reinsurance.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 账单异议明细行对象；异议必须逐项列明差异原因。
 */
public class BillDispute {

    private Long id;
    private Long billId;
    private String itemRef;
    private BigDecimal expectedAmount;
    private String reason;
    private Instant createdAt;

    public BillDispute() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBillId() {
        return billId;
    }

    public void setBillId(Long billId) {
        this.billId = billId;
    }

    public String getItemRef() {
        return itemRef;
    }

    public void setItemRef(String itemRef) {
        this.itemRef = itemRef;
    }

    public BigDecimal getExpectedAmount() {
        return expectedAmount;
    }

    public void setExpectedAmount(BigDecimal expectedAmount) {
        this.expectedAmount = expectedAmount;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
