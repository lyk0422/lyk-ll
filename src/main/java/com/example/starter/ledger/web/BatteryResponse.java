package com.example.starter.ledger.web;

import java.time.Instant;

/**
 * 电池资产视图。
 */
public class BatteryResponse {

    private String code;
    private String status;
    private String currentStation;
    private String boundVehicle;
    private String headEventHash;
    private Instant createdAt;
    private Instant updatedAt;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCurrentStation() {
        return currentStation;
    }

    public void setCurrentStation(String currentStation) {
        this.currentStation = currentStation;
    }

    public String getBoundVehicle() {
        return boundVehicle;
    }

    public void setBoundVehicle(String boundVehicle) {
        this.boundVehicle = boundVehicle;
    }

    public String getHeadEventHash() {
        return headEventHash;
    }

    public void setHeadEventHash(String headEventHash) {
        this.headEventHash = headEventHash;
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
