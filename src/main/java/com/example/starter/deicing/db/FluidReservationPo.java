package com.example.starter.deicing.db;

import com.example.starter.deicing.domain.ReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 两步法预占 fluid_reservation 的 PO。
 */
public class FluidReservationPo {

    private long id;
    /** 关联的除冰申请 requestId，唯一。 */
    private String requestId;
    private String fluidCode;
    /** 预占量（升）= 车位占用时长 × 配比。 */
    private BigDecimal quantityLiters;
    private ReservationStatus status;
    private Instant createdAt;
    private Instant updatedAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getFluidCode() {
        return fluidCode;
    }

    public void setFluidCode(String fluidCode) {
        this.fluidCode = fluidCode;
    }

    public BigDecimal getQuantityLiters() {
        return quantityLiters;
    }

    public void setQuantityLiters(BigDecimal quantityLiters) {
        this.quantityLiters = quantityLiters;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void setStatus(ReservationStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
