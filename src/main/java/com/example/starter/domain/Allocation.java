package com.example.starter.domain;

/**
 * 单机分摊结果（某一轮次）。
 * baseAvailableKw 分摊时该机组可用出力；targetPowerKw 分摊后保留出力目标；
 * curtailKw 压减出力；targetPowerKw 不低于技术出力下限。
 */
public class Allocation {

    private Long id;
    private Long orderId;
    private int cycleNo;
    private Long turbineId;
    private int baseAvailableKw;
    private int targetPowerKw;
    private int curtailKw;

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

    public int getCycleNo() {
        return cycleNo;
    }

    public void setCycleNo(int cycleNo) {
        this.cycleNo = cycleNo;
    }

    public Long getTurbineId() {
        return turbineId;
    }

    public void setTurbineId(Long turbineId) {
        this.turbineId = turbineId;
    }

    public int getBaseAvailableKw() {
        return baseAvailableKw;
    }

    public void setBaseAvailableKw(int baseAvailableKw) {
        this.baseAvailableKw = baseAvailableKw;
    }

    public int getTargetPowerKw() {
        return targetPowerKw;
    }

    public void setTargetPowerKw(int targetPowerKw) {
        this.targetPowerKw = targetPowerKw;
    }

    public int getCurtailKw() {
        return curtailKw;
    }

    public void setCurtailKw(int curtailKw) {
        this.curtailKw = curtailKw;
    }
}
