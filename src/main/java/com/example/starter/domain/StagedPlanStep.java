package com.example.starter.domain;

/**
 * 分段计划阶梯明细：某版本某分钟某机组的冻结功率（kW）。
 * 每分钟向目标移动不超过升降上限，最后一步恰到目标；历史版本保留可复算。
 */
public class StagedPlanStep {

    private Long id;
    private Long planId;
    private int planVersion;
    private int minuteNo;
    private Long turbineId;
    private int powerKw;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPlanId() {
        return planId;
    }

    public void setPlanId(Long planId) {
        this.planId = planId;
    }

    public int getPlanVersion() {
        return planVersion;
    }

    public void setPlanVersion(int planVersion) {
        this.planVersion = planVersion;
    }

    public int getMinuteNo() {
        return minuteNo;
    }

    public void setMinuteNo(int minuteNo) {
        this.minuteNo = minuteNo;
    }

    public Long getTurbineId() {
        return turbineId;
    }

    public void setTurbineId(Long turbineId) {
        this.turbineId = turbineId;
    }

    public int getPowerKw() {
        return powerKw;
    }

    public void setPowerKw(int powerKw) {
        this.powerKw = powerKw;
    }
}
