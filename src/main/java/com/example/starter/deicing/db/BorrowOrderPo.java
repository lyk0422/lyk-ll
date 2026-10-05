package com.example.starter.deicing.db;

import com.example.starter.deicing.domain.BorrowStatus;
import java.time.Instant;

/**
 * 跨班组借用单表 borrow_order 的 PO。所有时刻均为 UTC。
 */
public class BorrowOrderPo {

    private long id;
    /** 幂等键。 */
    private String requestId;
    /** 液罐编号，同一液罐同时刻仅允许一张未归还借用单。 */
    private String tankId;
    /** 除冰液编码。 */
    private String fluidCode;
    /** 借出量（升）。 */
    private int quantity;
    /** 借出班组。 */
    private String fromTeam;
    /** 借用班组。 */
    private String toTeam;
    private BorrowStatus status;
    /** 幂等指纹：requestId 与全部业务字段的 SHA-256。 */
    private String fingerprint;
    private Instant createdAt;
    /** 归还登记时刻（UTC），空值表示未归还。 */
    private Instant returnedAt;

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

    public String getTankId() {
        return tankId;
    }

    public void setTankId(String tankId) {
        this.tankId = tankId;
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

    public String getFromTeam() {
        return fromTeam;
    }

    public void setFromTeam(String fromTeam) {
        this.fromTeam = fromTeam;
    }

    public String getToTeam() {
        return toTeam;
    }

    public void setToTeam(String toTeam) {
        this.toTeam = toTeam;
    }

    public BorrowStatus getStatus() {
        return status;
    }

    public void setStatus(BorrowStatus status) {
        this.status = status;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getReturnedAt() {
        return returnedAt;
    }

    public void setReturnedAt(Instant returnedAt) {
        this.returnedAt = returnedAt;
    }
}
