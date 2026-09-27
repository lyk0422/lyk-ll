package com.example.starter.domain;

/**
 * 日前计划单段单机组核定计划行。
 * segmentIndex 15 分钟半开时段序号 0-95（UTC）；plannedPowerKw 非负整数 kW，不超过装机。
 */
public class DayAheadPlanEntry {

    private Long id;
    private Long planId;
    private int segmentIndex;
    private Long turbineId;
    private String turbineCode;
    private int plannedPowerKw;

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

    public int getSegmentIndex() {
        return segmentIndex;
    }

    public void setSegmentIndex(int segmentIndex) {
        this.segmentIndex = segmentIndex;
    }

    public Long getTurbineId() {
        return turbineId;
    }

    public void setTurbineId(Long turbineId) {
        this.turbineId = turbineId;
    }

    public String getTurbineCode() {
        return turbineCode;
    }

    public void setTurbineCode(String turbineCode) {
        this.turbineCode = turbineCode;
    }

    public int getPlannedPowerKw() {
        return plannedPowerKw;
    }

    public void setPlannedPowerKw(int plannedPowerKw) {
        this.plannedPowerKw = plannedPowerKw;
    }
}
