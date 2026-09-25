package com.example.starter.lc.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 信用证账户（合成数据）。所有时间字段为 UTC。
 */
public class LcAccount {

    private Long id;
    /** 信用证编号。 */
    private String lcNo;
    /** 开证申请人。 */
    private String applicant;
    /** 受益人。 */
    private String beneficiary;
    /** 币种，ISO 4217。 */
    private String currency;
    /** 信用证总金额。 */
    private BigDecimal amountTotal;
    /** 当前可用额度；成功付款后核减，单位与币种一致。 */
    private BigDecimal amountAvailable;
    /** 交单有效期截止时刻(UTC)，逾期不得交单/改单重交；为空表示长期有效。 */
    private LocalDateTime expiryUtc;
    private LocalDateTime createdAtUtc;
    private LocalDateTime updatedAtUtc;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLcNo() {
        return lcNo;
    }

    public void setLcNo(String lcNo) {
        this.lcNo = lcNo;
    }

    public String getApplicant() {
        return applicant;
    }

    public void setApplicant(String applicant) {
        this.applicant = applicant;
    }

    public String getBeneficiary() {
        return beneficiary;
    }

    public void setBeneficiary(String beneficiary) {
        this.beneficiary = beneficiary;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getAmountTotal() {
        return amountTotal;
    }

    public void setAmountTotal(BigDecimal amountTotal) {
        this.amountTotal = amountTotal;
    }

    public BigDecimal getAmountAvailable() {
        return amountAvailable;
    }

    public void setAmountAvailable(BigDecimal amountAvailable) {
        this.amountAvailable = amountAvailable;
    }

    public LocalDateTime getExpiryUtc() {
        return expiryUtc;
    }

    public void setExpiryUtc(LocalDateTime expiryUtc) {
        this.expiryUtc = expiryUtc;
    }

    public LocalDateTime getCreatedAtUtc() {
        return createdAtUtc;
    }

    public void setCreatedAtUtc(LocalDateTime createdAtUtc) {
        this.createdAtUtc = createdAtUtc;
    }

    public LocalDateTime getUpdatedAtUtc() {
        return updatedAtUtc;
    }

    public void setUpdatedAtUtc(LocalDateTime updatedAtUtc) {
        this.updatedAtUtc = updatedAtUtc;
    }
}
