package com.example.starter.domain;

/**
 * 日前计划明细：某版本某时段某机组的核定计划出力。
 * periodIndex 时段序号 1..96（每段 15 分钟半开区间）；plannedKw 非负且不超过机组装机容量，单位 kW。
 */
public class DayAheadPlanItem {

    private Long id;
    private Long planId;
    private int periodIndex;
    private Long turbineId;
    private int plannedKw;

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

    public int getPeriodIndex() {
        return periodIndex;
    }

    public void setPeriodIndex(int periodIndex) {
        this.periodIndex = periodIndex;
    }

    public Long getTurbineId() {
        return turbineId;
    }

    public void setTurbineId(Long turbineId) {
        this.turbineId = turbineId;
    }

    public int getPlannedKw() {
        return plannedKw;
    }

    public void setPlannedKw(int plannedKw) {
        this.plannedKw = plannedKw;
    }
}
