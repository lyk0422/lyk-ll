package com.example.starter.dye.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 称量链 PO：放大配方后的投料称量明细，可按缸次复算。
 */
public class WeighRecordPO {
    private Long id;
    private Long runId;
    private String requestId;
    private String dyestuffCode;
    private BigDecimal plannedGrams;
    private BigDecimal actualGrams;
    private BigDecimal deviationRatio;
    private boolean accepted;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getDyestuffCode() { return dyestuffCode; }
    public void setDyestuffCode(String dyestuffCode) { this.dyestuffCode = dyestuffCode; }
    public BigDecimal getPlannedGrams() { return plannedGrams; }
    public void setPlannedGrams(BigDecimal plannedGrams) { this.plannedGrams = plannedGrams; }
    public BigDecimal getActualGrams() { return actualGrams; }
    public void setActualGrams(BigDecimal actualGrams) { this.actualGrams = actualGrams; }
    public BigDecimal getDeviationRatio() { return deviationRatio; }
    public void setDeviationRatio(BigDecimal deviationRatio) { this.deviationRatio = deviationRatio; }
    public boolean isAccepted() { return accepted; }
    public void setAccepted(boolean accepted) { this.accepted = accepted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
