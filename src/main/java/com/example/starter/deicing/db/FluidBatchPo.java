package com.example.starter.deicing.db;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 补料入库批次 fluid_batch 的 PO。效期截止（UTC）到期及之后视为耗尽。
 */
public class FluidBatchPo {

    private long id;
    private String batchNo;
    private String fluidCode;
    /** 批次来源：OPENING 期初 / INBOUND 补料入库。 */
    private String source;
    /** 入库量（升）。 */
    private BigDecimal quantityLiters;
    /** 剩余量（升），结算超扣时可为负表示断供透支。 */
    private BigDecimal remainingLiters;
    /** 效期截止（UTC）。 */
    private Instant expiryTime;
    private Instant createdAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public String getFluidCode() {
        return fluidCode;
    }

    public void setFluidCode(String fluidCode) {
        this.fluidCode = fluidCode;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public BigDecimal getQuantityLiters() {
        return quantityLiters;
    }

    public void setQuantityLiters(BigDecimal quantityLiters) {
        this.quantityLiters = quantityLiters;
    }

    public BigDecimal getRemainingLiters() {
        return remainingLiters;
    }

    public void setRemainingLiters(BigDecimal remainingLiters) {
        this.remainingLiters = remainingLiters;
    }

    public Instant getExpiryTime() {
        return expiryTime;
    }

    public void setExpiryTime(Instant expiryTime) {
        this.expiryTime = expiryTime;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
