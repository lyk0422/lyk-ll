package com.example.starter.domain;

/**
 * 绑定模式指令的冻结基数：受理时冻结各机组核定计划与实时可用出力。
 * 后续新计划发布不重算已受理指令；超时重分摊仍引用 plannedKw，仅实时可用值随回报更新。
 */
public class OrderPlanBase {

    private Long id;
    private long orderId;
    private long turbineId;
    private int plannedKw;
    private int availableKw;

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

    public int getAvailableKw() {
        return availableKw;
    }

    public void setAvailableKw(int availableKw) {
        this.availableKw = availableKw;
    }
}
