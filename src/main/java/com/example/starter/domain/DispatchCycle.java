package com.example.starter.domain;

import java.time.Instant;

/**
 * 下发轮次：每次分摊下发（含告警后重分摊）生成一轮。
 * dueAt 下发后满 10 分钟的判定时刻（恰满即判定）。
 */
public class DispatchCycle {

    private Long id;
    private Long orderId;
    private int cycleNo;
    private Instant dispatchedAt;
    private Instant dueAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public int getCycleNo() {
        return cycleNo;
    }

    public void setCycleNo(int cycleNo) {
        this.cycleNo = cycleNo;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }

    public void setDispatchedAt(Instant dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public void setDueAt(Instant dueAt) {
        this.dueAt = dueAt;
    }
}
