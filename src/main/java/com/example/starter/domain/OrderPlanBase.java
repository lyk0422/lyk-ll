package com.example.starter.domain;

/**
 * 指令计划基数证据（受理时冻结，随指令不可变）。
 * plannedKw 冻结的该段核定计划（kW）；availableKw 受理时机组实时可用出力（kW）；
 * baseKw 可参与出力 = min(availableKw, plannedKw)，限电分摊以此为基数。
 */
public class OrderPlanBase {

    private Long id;
    private Long orderId;
    private Long planId;
    private Long turbineId;
    private int plannedKw;
    private int availableKw;
    private int baseKw;

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

    public Long getPlanId() {
        return planId;
    }

    public void setPlanId(Long planId) {
        this.planId = planId;
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

    public int getAvailableKw() {
        return availableKw;
    }

    public void setAvailableKw(int availableKw) {
        this.availableKw = availableKw;
    }

    public int getBaseKw() {
        return baseKw;
    }

    public void setBaseKw(int baseKw) {
        this.baseKw = baseKw;
    }
}
