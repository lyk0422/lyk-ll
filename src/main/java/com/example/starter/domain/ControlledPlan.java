package com.example.starter.domain;

import java.time.Instant;

/**
 * 受控分段计划版本。
 * planVersion 同指令从 1 起，修改（重规划）成功加 1；startAt 为本版本启动 UTC 时刻，
 * 第 k 分钟计划时刻 = startAt + k 分钟；confirmedMinute 为已整分钟确认到的序号（0 起）。
 */
public class ControlledPlan {

    private Long id;
    private long orderId;
    private int planVersion;
    private ControlledPlanStatus status;
    private Instant startAt;
    private int durationMinutes;
    private int confirmedMinute;
    private String requestId;
    private String fingerprint;
    private Instant createdAt;
    private Instant closedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getOrderId() {
        return orderId;
    }

    public void setOrderId(long orderId) {
        this.orderId = orderId;
    }

    public int getPlanVersion() {
        return planVersion;
    }

    public void setPlanVersion(int planVersion) {
        this.planVersion = planVersion;
    }

    public ControlledPlanStatus getStatus() {
        return status;
    }

    public void setStatus(ControlledPlanStatus status) {
        this.status = status;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public void setStartAt(Instant startAt) {
        this.startAt = startAt;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public int getConfirmedMinute() {
        return confirmedMinute;
    }

    public void setConfirmedMinute(int confirmedMinute) {
        this.confirmedMinute = confirmedMinute;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
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

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }
}
