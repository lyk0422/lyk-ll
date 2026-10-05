package com.example.starter.deicing.db;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 除冰液配比表 deicing_fluid 的 PO。
 */
public class DeicingFluidPo {

    private String fluidCode;
    private String fluidName;
    /** 合成配比：每分钟作业消耗（升）。 */
    private BigDecimal consumptionPerMinuteLiters;
    /** 期初库存（升）。 */
    private BigDecimal openingLiters;
    private Instant createdAt;

    public String getFluidCode() {
        return fluidCode;
    }

    public void setFluidCode(String fluidCode) {
        this.fluidCode = fluidCode;
    }

    public String getFluidName() {
        return fluidName;
    }

    public void setFluidName(String fluidName) {
        this.fluidName = fluidName;
    }

    public BigDecimal getConsumptionPerMinuteLiters() {
        return consumptionPerMinuteLiters;
    }

    public void setConsumptionPerMinuteLiters(BigDecimal consumptionPerMinuteLiters) {
        this.consumptionPerMinuteLiters = consumptionPerMinuteLiters;
    }

    public BigDecimal getOpeningLiters() {
        return openingLiters;
    }

    public void setOpeningLiters(BigDecimal openingLiters) {
        this.openingLiters = openingLiters;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
