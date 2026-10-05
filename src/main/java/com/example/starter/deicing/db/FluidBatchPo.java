package com.example.starter.deicing.db;

import java.time.Instant;

/**
 * 补料入库批次表 fluid_batch 的 PO。所有时刻均为 UTC。
 */
public class FluidBatchPo {

    private long id;
    /** 批次号，唯一。 */
    private String batchNo;
    /** 除冰液编码。 */
    private String fluidCode;
    /** 入库量（升）。 */
    private int quantityIn;
    /** 剩余量（升）= 入库量减已消耗。 */
    private int quantityAvailable;
    /** 效期截止（UTC），到期及之后不得参与预占与结算。 */
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

    public int getQuantityIn() {
        return quantityIn;
    }

    public void setQuantityIn(int quantityIn) {
        this.quantityIn = quantityIn;
    }

    public int getQuantityAvailable() {
        return quantityAvailable;
    }

    public void setQuantityAvailable(int quantityAvailable) {
        this.quantityAvailable = quantityAvailable;
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
