package com.example.starter.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 限电指令。
 * requestId 外部请求幂等键（全局唯一）；fingerprint 覆盖 requestId 与全部业务字段的指纹；
 * status 当前生命周期状态；currentCycleNo 当前执行轮次（初次下发为 1，告警重分摊递增）；
 * supersededByOrderId 作废本指令的新指令 id（非空表示已被新指令原子切换）。
 * planId/planVersion/planDate/periodIndex 为计划绑定信息（受理时冻结），全部为空表示未绑定计划。
 */
public class CurtailOrder {

    private Long id;
    private String orderNo;
    private String farmCode;
    private String requestId;
    private String fingerprint;
    private int targetPowerKw;
    private OrderStatus status;
    private int currentCycleNo;
    private Long supersededByOrderId;
    private Long planId;
    private Integer planVersion;
    private LocalDate planDate;
    private Integer periodIndex;
    private Instant receivedAt;
    private Instant settledAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getFarmCode() {
        return farmCode;
    }

    public void setFarmCode(String farmCode) {
        this.farmCode = farmCode;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public int getTargetPowerKw() {
        return targetPowerKw;
    }

    public void setTargetPowerKw(int targetPowerKw) {
        this.targetPowerKw = targetPowerKw;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public int getCurrentCycleNo() {
        return currentCycleNo;
    }

    public void setCurrentCycleNo(int currentCycleNo) {
        this.currentCycleNo = currentCycleNo;
    }

    public Long getSupersededByOrderId() {
        return supersededByOrderId;
    }

    public void setSupersededByOrderId(Long supersededByOrderId) {
        this.supersededByOrderId = supersededByOrderId;
    }

    public Long getPlanId() {
        return planId;
    }

    public void setPlanId(Long planId) {
        this.planId = planId;
    }

    public Integer getPlanVersion() {
        return planVersion;
    }

    public void setPlanVersion(Integer planVersion) {
        this.planVersion = planVersion;
    }

    public LocalDate getPlanDate() {
        return planDate;
    }

    public void setPlanDate(LocalDate planDate) {
        this.planDate = planDate;
    }

    public Integer getPeriodIndex() {
        return periodIndex;
    }

    public void setPeriodIndex(Integer periodIndex) {
        this.periodIndex = periodIndex;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(Instant settledAt) {
        this.settledAt = settledAt;
    }
}
