package com.example.starter.domain;

import java.time.Instant;

/**
 * 受控分段计划：限电指令的可选分段执行模式。
 * planVersion 从 1 起，调用方修改带 expectedVersion、成功加 1，超时重分配亦加 1；
 * startAt 为当前版本启动 UTC 时刻，回执分钟时刻 = startAt + 序号分钟；
 * currentMinute 为当前版本已确认分钟序号（0 表示尚未确认）。
 */
public class StagedPlan {

    private Long id;
    private Long orderId;
    private int planVersion;
    private StagedPlanStatus status;
    private Instant startAt;
    private int currentMinute;
    private int durationMinutes;
    private Instant createdAt;
    private Instant updatedAt;

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

    public int getPlanVersion() {
        return planVersion;
    }

    public void setPlanVersion(int planVersion) {
        this.planVersion = planVersion;
    }

    public StagedPlanStatus getStatus() {
        return status;
    }

    public void setStatus(StagedPlanStatus status) {
        this.status = status;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public void setStartAt(Instant startAt) {
        this.startAt = startAt;
    }

    public int getCurrentMinute() {
        return currentMinute;
    }

    public void setCurrentMinute(int currentMinute) {
        this.currentMinute = currentMinute;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
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
