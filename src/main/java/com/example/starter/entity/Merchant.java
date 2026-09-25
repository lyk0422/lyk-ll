package com.example.starter.entity;

import java.math.BigDecimal;
import java.time.Instant;

/** 商户（合成数据）。 */
public class Merchant {
    private String merchantNo;
    private String merchantName;
    /** 手续费率，如 0.006000 表示千分之六。 */
    private BigDecimal feeRate;
    private String status;
    private Instant createdAt;

    public String getMerchantNo() { return merchantNo; }
    public void setMerchantNo(String merchantNo) { this.merchantNo = merchantNo; }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public BigDecimal getFeeRate() { return feeRate; }
    public void setFeeRate(BigDecimal feeRate) { this.feeRate = feeRate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
