package com.example.starter.entity;

import java.time.Instant;
import java.time.LocalDate;

/** 挂账调账指令留痕。 */
public class Adjustment {
    private Long id;
    private String adjustNo;
    private Long txnId;
    private LocalDate fromBatchDate;
    private LocalDate toBatchDate;
    /** 金额单位：分。 */
    private long amount;
    /** DONE 调账完成。 */
    private String status;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAdjustNo() { return adjustNo; }
    public void setAdjustNo(String adjustNo) { this.adjustNo = adjustNo; }
    public Long getTxnId() { return txnId; }
    public void setTxnId(Long txnId) { this.txnId = txnId; }
    public LocalDate getFromBatchDate() { return fromBatchDate; }
    public void setFromBatchDate(LocalDate fromBatchDate) { this.fromBatchDate = fromBatchDate; }
    public LocalDate getToBatchDate() { return toBatchDate; }
    public void setToBatchDate(LocalDate toBatchDate) { this.toBatchDate = toBatchDate; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
