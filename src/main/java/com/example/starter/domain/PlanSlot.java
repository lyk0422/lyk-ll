package com.example.starter.domain;

/**
 * 日前计划单时段单机组计划值。
 * slotIndex 0-95，对应 planDate + slotIndex*15 分钟起的 15 分钟半开时段（UTC）；
 * plannedKw 核定计划出力（kW），非负且不超过机组装机容量。
 */
public class PlanSlot {

    private Long id;
    private long planId;
    private int slotIndex;
    private long turbineId;
    private int plannedKw;

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

    public int getSlotIndex() {
        return slotIndex;
    }

    public void setSlotIndex(int slotIndex) {
        this.slotIndex = slotIndex;
    }

    public long getTurbineId() {
        return turbineId;
    }

    public void setTurbineId(long turbineId) {
        this.turbineId = turbineId;
    }

    public int getPlannedKw() {
        return plannedKw;
    }

    public void setPlannedKw(int plannedKw) {
        this.plannedKw = plannedKw;
    }
}
