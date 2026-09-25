package com.example.starter.domain;

import java.time.Instant;

/**
 * 机组执行回报。按“机组＋回报时刻”去重；同机组乱序回报按时刻归位（读取曲线按时刻排序）。
 * measuredAt 回报中的出力时刻；receivedAt 服务收到时刻；powerKw 实测出力。
 */
public class ExecutionReport {

    private Long id;
    private String requestId;
    private Long orderId;
    private Long turbineId;
    private Instant measuredAt;
    private int powerKw;
    private Instant receivedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getTurbineId() {
        return turbineId;
    }

    public void setTurbineId(Long turbineId) {
        this.turbineId = turbineId;
    }

    public Instant getMeasuredAt() {
        return measuredAt;
    }

    public void setMeasuredAt(Instant measuredAt) {
        this.measuredAt = measuredAt;
    }

    public int getPowerKw() {
        return powerKw;
    }

    public void setPowerKw(int powerKw) {
        this.powerKw = powerKw;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }
}
