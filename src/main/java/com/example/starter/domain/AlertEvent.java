package com.example.starter.domain;

import java.time.Instant;

/**
 * 告警事件链：下发满 10 分钟未达目标触发，重分摊产生下一轮，链条可复算。
 * cycleNo 触发告警的轮次；farmActualKw 告警时刻全场实测出力合计；
 * reallocatedCycleNo 触发的重分摊新轮次（末条告警可能尚未重分摊时为空）。
 */
public class AlertEvent {

    private Long id;
    private Long orderId;
    private int cycleNo;
    private AlertType type;
    private Instant firedAt;
    private Integer farmActualKw;
    private Integer reallocatedCycleNo;

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

    public AlertType getType() {
        return type;
    }

    public void setType(AlertType type) {
        this.type = type;
    }

    public Instant getFiredAt() {
        return firedAt;
    }

    public void setFiredAt(Instant firedAt) {
        this.firedAt = firedAt;
    }

    public Integer getFarmActualKw() {
        return farmActualKw;
    }

    public void setFarmActualKw(Integer farmActualKw) {
        this.farmActualKw = farmActualKw;
    }

    public Integer getReallocatedCycleNo() {
        return reallocatedCycleNo;
    }

    public void setReallocatedCycleNo(Integer reallocatedCycleNo) {
        this.reallocatedCycleNo = reallocatedCycleNo;
    }
}
