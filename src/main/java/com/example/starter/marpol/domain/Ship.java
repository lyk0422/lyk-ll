package com.example.starter.marpol.domain;

import java.time.Instant;

/**
 * 船舶（表 ship），合成数据。
 */
public class Ship {

    private String shipId;
    private String shipName;
    private Instant createdAt;

    public String getShipId() {
        return shipId;
    }

    public void setShipId(String shipId) {
        this.shipId = shipId;
    }

    public String getShipName() {
        return shipName;
    }

    public void setShipName(String shipName) {
        this.shipName = shipName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
