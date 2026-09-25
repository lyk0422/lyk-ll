package com.example.starter.dye.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 迟到测色事件 PO：缸次归档终态后到达的测色数据只登记事件，不改缸次状态。
 */
public class LateColorEventPO {
    private Long id;
    private Long runId;
    private String runNo;
    private BigDecimal deltaE;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public String getRunNo() { return runNo; }
    public void setRunNo(String runNo) { this.runNo = runNo; }
    public BigDecimal getDeltaE() { return deltaE; }
    public void setDeltaE(BigDecimal deltaE) { this.deltaE = deltaE; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
