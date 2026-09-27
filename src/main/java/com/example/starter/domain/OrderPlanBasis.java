package com.example.starter.domain;

/**
 * 计划绑定指令受理时逐机组冻结的基数证据行。
 * plannedPowerKw 冻结的该段核定计划出力；realtimeAvailableKw 受理瞬间实时可用出力；
 * participatingKw 可参与出力 = min(实时可用, 核定计划)。
 */
public class OrderPlanBasis {

    private Long id;
    private Long orderId;
    private Long turbineId;
    private String turbineCode;
    private int plannedPowerKw;
    private int realtimeAvailableKw;
    private int participatingKw;

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

    public int getRealtimeAvailableKw() {
        return realtimeAvailableKw;
    }

    public void setRealtimeAvailableKw(int realtimeAvailableKw) {
        this.realtimeAvailableKw = realtimeAvailableKw;
    }

    public int getParticipatingKw() {
        return participatingKw;
    }

    public void setParticipatingKw(int participatingKw) {
        this.participatingKw = participatingKw;
    }
}
