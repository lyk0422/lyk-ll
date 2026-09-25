package com.example.starter.dye.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存台账 PO：每次扣减/回补留痕，按染化料顺序累加即可复算库存。
 * changeGrams 投料扣减为负、回补为正，单位克。
 */
public class StockMovementPO {
    private Long id;
    private String requestId;
    private String dyestuffCode;
    private BigDecimal changeGrams;
    private BigDecimal balanceGrams;
    private String runNo;
    private String reason;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getDyestuffCode() { return dyestuffCode; }
    public void setDyestuffCode(String dyestuffCode) { this.dyestuffCode = dyestuffCode; }
    public BigDecimal getChangeGrams() { return changeGrams; }
    public void setChangeGrams(BigDecimal changeGrams) { this.changeGrams = changeGrams; }
    public BigDecimal getBalanceGrams() { return balanceGrams; }
    public void setBalanceGrams(BigDecimal balanceGrams) { this.balanceGrams = balanceGrams; }
    public String getRunNo() { return runNo; }
    public void setRunNo(String runNo) { this.runNo = runNo; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
