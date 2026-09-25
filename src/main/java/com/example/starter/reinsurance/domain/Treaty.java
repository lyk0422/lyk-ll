package com.example.starter.reinsurance.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 再保比例合约行对象。
 */
public class Treaty {

    private Long id;
    private String contractNo;
    private BigDecimal cessionRate;
    /** 每张保单再保人责任限额（元）；{@code null} 表示无限额。 */
    private BigDecimal perPolicyLimit;
    private Instant createdAt;

    public Treaty() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getContractNo() {
        return contractNo;
    }

    public void setContractNo(String contractNo) {
        this.contractNo = contractNo;
    }

    public BigDecimal getCessionRate() {
        return cessionRate;
    }

    public void setCessionRate(BigDecimal cessionRate) {
        this.cessionRate = cessionRate;
    }

    public BigDecimal getPerPolicyLimit() {
        return perPolicyLimit;
    }

    public void setPerPolicyLimit(BigDecimal perPolicyLimit) {
        this.perPolicyLimit = perPolicyLimit;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
