package com.example.starter.domain;

/**
 * 受控分段计划机组参数：登记时冻结的起始实际功率、每分钟升降上限、期限与最终分摊目标。
 */
public class ControlledPlanTurbine {

    private Long id;
    private long planId;
    private long turbineId;
    private int startPowerKw;
    private int rampRateKwPerMin;
    private int durationMinutes;
    private int targetPowerKw;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getPlanId() {
        return planId;
    }

    public void setPlanId(long planId) {
        this.planId = planId;
    }

    public long getTurbineId() {
        return turbineId;
    }

    public void setTurbineId(long turbineId) {
        this.turbineId = turbineId;
    }

    public int getStartPowerKw() {
        return startPowerKw;
    }

    public void setStartPowerKw(int startPowerKw) {
        this.startPowerKw = startPowerKw;
    }

    public int getRampRateKwPerMin() {
        return rampRateKwPerMin;
    }

    public void setRampRateKwPerMin(int rampRateKwPerMin) {
        this.rampRateKwPerMin = rampRateKwPerMin;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public int getTargetPowerKw() {
        return targetPowerKw;
    }

    public void setTargetPowerKw(int targetPowerKw) {
        this.targetPowerKw = targetPowerKw;
    }
}
