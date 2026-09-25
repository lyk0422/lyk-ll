package com.example.starter.entity;

import java.time.Instant;
import java.time.LocalDate;

/** 日切清算批次。 */
public class SettlementBatch {
    private LocalDate batchDate;
    /** OPEN / CLOSED。 */
    private String status;
    private Instant closedAt;
    private int merchantCount;
    /** 金额单位：分。 */
    private long totalAmount;
    private long totalFee;
    private long settledAmount;
    private Instant createdAt;

    public LocalDate getBatchDate() { return batchDate; }
    public void setBatchDate(LocalDate batchDate) { this.batchDate = batchDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
    public int getMerchantCount() { return merchantCount; }
    public void setMerchantCount(int merchantCount) { this.merchantCount = merchantCount; }
    public long getTotalAmount() { return totalAmount; }
    public void setTotalAmount(long totalAmount) { this.totalAmount = totalAmount; }
    public long getTotalFee() { return totalFee; }
    public void setTotalFee(long totalFee) { this.totalFee = totalFee; }
    public long getSettledAmount() { return settledAmount; }
    public void setSettledAmount(long settledAmount) { this.settledAmount = settledAmount; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
