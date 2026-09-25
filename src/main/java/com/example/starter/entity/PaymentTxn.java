package com.example.starter.entity;

import java.time.Instant;
import java.time.LocalDate;

/** 支付交易流水。 */
public class PaymentTxn {
    private Long id;
    private String requestId;
    private String fingerprint;
    private String channelFlowNo;
    private String merchantNo;
    /** 金额单位：分。 */
    private long amount;
    private long fee;
    private LocalDate batchDate;
    /** 调账前的原批次；非调账交易为 null。 */
    private LocalDate originBatchDate;
    /** POSTED / SUSPENDED / MOVED。 */
    private String status;
    private Instant occurredAt;
    private Instant createdAt;
    private Instant movedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public String getChannelFlowNo() { return channelFlowNo; }
    public void setChannelFlowNo(String channelFlowNo) { this.channelFlowNo = channelFlowNo; }
    public String getMerchantNo() { return merchantNo; }
    public void setMerchantNo(String merchantNo) { this.merchantNo = merchantNo; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public long getFee() { return fee; }
    public void setFee(long fee) { this.fee = fee; }
    public LocalDate getBatchDate() { return batchDate; }
    public void setBatchDate(LocalDate batchDate) { this.batchDate = batchDate; }
    public LocalDate getOriginBatchDate() { return originBatchDate; }
    public void setOriginBatchDate(LocalDate originBatchDate) { this.originBatchDate = originBatchDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getMovedAt() { return movedAt; }
    public void setMovedAt(Instant movedAt) { this.movedAt = movedAt; }
}
