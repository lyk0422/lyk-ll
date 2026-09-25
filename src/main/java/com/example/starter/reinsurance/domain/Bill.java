package com.example.starter.reinsurance.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 再保分保账单行对象。
 */
public class Bill {

    private Long id;
    private String billNo;
    private Long contractId;
    private String period;
    private BillType billType;
    private String refBillNo;
    private BillStatus status;
    private boolean cedantConfirmed;
    private boolean reinsurerConfirmed;
    private BigDecimal premiumTotal;
    private BigDecimal recoveryTotal;
    private BigDecimal netTotal;
    private Instant createdAt;
    private Instant sealedAt;

    public Bill() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBillNo() {
        return billNo;
    }

    public void setBillNo(String billNo) {
        this.billNo = billNo;
    }

    public Long getContractId() {
        return contractId;
    }

    public void setContractId(Long contractId) {
        this.contractId = contractId;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public BillType getBillType() {
        return billType;
    }

    public void setBillType(BillType billType) {
        this.billType = billType;
    }

    public String getRefBillNo() {
        return refBillNo;
    }

    public void setRefBillNo(String refBillNo) {
        this.refBillNo = refBillNo;
    }

    public BillStatus getStatus() {
        return status;
    }

    public void setStatus(BillStatus status) {
        this.status = status;
    }

    public boolean isCedantConfirmed() {
        return cedantConfirmed;
    }

    public void setCedantConfirmed(boolean cedantConfirmed) {
        this.cedantConfirmed = cedantConfirmed;
    }

    public boolean isReinsurerConfirmed() {
        return reinsurerConfirmed;
    }

    public void setReinsurerConfirmed(boolean reinsurerConfirmed) {
        this.reinsurerConfirmed = reinsurerConfirmed;
    }

    public BigDecimal getPremiumTotal() {
        return premiumTotal;
    }

    public void setPremiumTotal(BigDecimal premiumTotal) {
        this.premiumTotal = premiumTotal;
    }

    public BigDecimal getRecoveryTotal() {
        return recoveryTotal;
    }

    public void setRecoveryTotal(BigDecimal recoveryTotal) {
        this.recoveryTotal = recoveryTotal;
    }

    public BigDecimal getNetTotal() {
        return netTotal;
    }

    public void setNetTotal(BigDecimal netTotal) {
        this.netTotal = netTotal;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getSealedAt() {
        return sealedAt;
    }

    public void setSealedAt(Instant sealedAt) {
        this.sealedAt = sealedAt;
    }
}
