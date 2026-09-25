package com.example.starter.entity;

import java.time.Instant;
import java.time.LocalDate;

/** 商户清算单（封账时按商户汇总生成）。 */
public class SettlementOrder {
    private Long id;
    private LocalDate batchDate;
    private String merchantNo;
    private int txnCount;
    /** 金额单位：分。 */
    private long totalAmount;
    private long totalFee;
    private long settledAmount;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getBatchDate() { return batchDate; }
    public void setBatchDate(LocalDate batchDate) { this.batchDate = batchDate; }
    public String getMerchantNo() { return merchantNo; }
    public void setMerchantNo(String merchantNo) { this.merchantNo = merchantNo; }
    public int getTxnCount() { return txnCount; }
    public void setTxnCount(int txnCount) { this.txnCount = txnCount; }
    public long getTotalAmount() { return totalAmount; }
    public void setTotalAmount(long totalAmount) { this.totalAmount = totalAmount; }
    public long getTotalFee() { return totalFee; }
    public void setTotalFee(long totalFee) { this.totalFee = totalFee; }
    public long getSettledAmount() { return settledAmount; }
    public void setSettledAmount(long settledAmount) { this.settledAmount = settledAmount; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
