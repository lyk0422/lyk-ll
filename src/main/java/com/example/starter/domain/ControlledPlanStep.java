package com.example.starter.domain;

/**
 * 受控分段阶梯：某计划版本下某机组某一分钟的冻结功率。
 */
public class ControlledPlanStep {

    private Long id;
    private long planId;
    private long turbineId;
    private int minuteNo;
    private int powerKw;

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

    public int getMinuteNo() {
        return minuteNo;
    }

    public void setMinuteNo(int minuteNo) {
        this.minuteNo = minuteNo;
    }

    public int getPowerKw() {
        return powerKw;
    }

    public void setPowerKw(int powerKw) {
        this.powerKw = powerKw;
    }
}
