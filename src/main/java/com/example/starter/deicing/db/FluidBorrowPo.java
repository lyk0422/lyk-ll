package com.example.starter.deicing.db;

import com.example.starter.deicing.domain.BorrowStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 跨班组借用单 fluid_borrow 的 PO。
 */
public class FluidBorrowPo {

    private long id;
    /** 幂等键，唯一。 */
    private String requestId;
    private String fluidCode;
    /** 出借液罐编号，同时刻仅允许一张未归还借用单。 */
    private String tankCode;
    /** 借出量（升）。 */
    private BigDecimal quantityLiters;
    private String fromTeam;
    private String toTeam;
    private BorrowStatus status;
    /** 幂等指纹：requestId 与全部业务字段 SHA-256。 */
    private String fingerprint;
    private Instant createdAt;
    /** 归还登记时刻（UTC），空表示未归还。 */
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

    public String getFluidCode() {
        return fluidCode;
    }

    public void setFluidCode(String fluidCode) {
        this.fluidCode = fluidCode;
    }

    public String getTankCode() {
        return tankCode;
    }

    public void setTankCode(String tankCode) {
        this.tankCode = tankCode;
    }

    public BigDecimal getQuantityLiters() {
        return quantityLiters;
    }

    public void setQuantityLiters(BigDecimal quantityLiters) {
        this.quantityLiters = quantityLiters;
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
