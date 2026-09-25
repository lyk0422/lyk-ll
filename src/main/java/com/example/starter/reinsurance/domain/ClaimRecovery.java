package com.example.starter.reinsurance.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 赔案摊回状态行对象：未决预提与结案实摊。
 */
public class ClaimRecovery {

    private Long id;
    private Long claimId;
    private Long policyId;
    private Long contractId;
    private BigDecimal provisionAmount;
    private BigDecimal settledAmount;
    /** 因保单责任限额而留在分出公司的金额（元）。 */
    private BigDecimal cappedExcess;
    private String recoveryStatus;
    private Instant updatedAt;

    public ClaimRecovery() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public void setClaimId(Long claimId) {
        this.claimId = claimId;
    }

    public Long getPolicyId() {
        return policyId;
    }

    public void setPolicyId(Long policyId) {
        this.policyId = policyId;
    }

    public Long getContractId() {
        return contractId;
    }

    public void setContractId(Long contractId) {
        this.contractId = contractId;
    }

    public BigDecimal getProvisionAmount() {
        return provisionAmount;
    }

    public void setProvisionAmount(BigDecimal provisionAmount) {
        this.provisionAmount = provisionAmount;
    }

    public BigDecimal getSettledAmount() {
        return settledAmount;
    }

    public void setSettledAmount(BigDecimal settledAmount) {
        this.settledAmount = settledAmount;
    }

    public BigDecimal getCappedExcess() {
        return cappedExcess;
    }

    public void setCappedExcess(BigDecimal cappedExcess) {
        this.cappedExcess = cappedExcess;
    }

    public String getRecoveryStatus() {
        return recoveryStatus;
    }

    public void setRecoveryStatus(String recoveryStatus) {
        this.recoveryStatus = recoveryStatus;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
