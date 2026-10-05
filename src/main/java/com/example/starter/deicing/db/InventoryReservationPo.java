package com.example.starter.deicing.db;

import com.example.starter.deicing.domain.ReservationStatus;
import java.time.Instant;

/**
 * 库存预占表 inventory_reservation 的 PO。所有时刻均为 UTC。
 */
public class InventoryReservationPo {

    private long id;
    /** 关联的除冰申请 requestId。 */
    private String requestId;
    /** 除冰液编码。 */
    private String fluidCode;
    /** 预占量（升）= 所需车位数 × 作业分钟 × 配比。 */
    private int quantity;
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

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
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
