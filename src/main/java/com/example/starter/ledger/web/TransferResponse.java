package com.example.starter.ledger.web;

import java.time.Instant;

/**
 * 调拨单视图。
 */
public class TransferResponse {

    private String orderNo;
    private String batteryCode;
    private String fromStation;
    private String toStation;
    private String status;
    private Instant dispatchedAt;
    private Instant receivedAt;
    private Instant exceptionAt;
    private Instant writtenOffAt;
    private String writeoffReason;

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getBatteryCode() {
        return batteryCode;
    }

    public void setBatteryCode(String batteryCode) {
        this.batteryCode = batteryCode;
    }

    public String getFromStation() {
        return fromStation;
    }

    public void setFromStation(String fromStation) {
        this.fromStation = fromStation;
    }

    public String getToStation() {
        return toStation;
    }

    public void setToStation(String toStation) {
        this.toStation = toStation;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }

    public void setDispatchedAt(Instant dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public Instant getExceptionAt() {
        return exceptionAt;
    }

    public void setExceptionAt(Instant exceptionAt) {
        this.exceptionAt = exceptionAt;
    }

    public Instant getWrittenOffAt() {
        return writtenOffAt;
    }

    public void setWrittenOffAt(Instant writtenOffAt) {
        this.writtenOffAt = writtenOffAt;
    }

    public String getWriteoffReason() {
        return writeoffReason;
    }

    public void setWriteoffReason(String writeoffReason) {
        this.writeoffReason = writeoffReason;
    }
}
