package com.example.starter.marpol.domain;

import java.time.Instant;

/**
 * 船舶航次（表 voyage）。航次关闭后新增排放记录一律拒绝。
 */
public class Voyage {

    private String shipId;
    private String voyageNo;
    /** OPEN / CLOSED。 */
    private String status;
    /** 关闭时刻（UTC），未关闭为 null。 */
    private Instant closedAt;
    private Instant createdAt;

    public boolean isClosed() {
        return "CLOSED".equals(status);
    }

    public String getShipId() {
        return shipId;
    }

    public void setShipId(String shipId) {
        this.shipId = shipId;
    }

    public String getVoyageNo() {
        return voyageNo;
    }

    public void setVoyageNo(String voyageNo) {
        this.voyageNo = voyageNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
