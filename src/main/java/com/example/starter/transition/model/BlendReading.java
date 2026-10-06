package com.example.starter.transition.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 实际掺配读数：按订单与采样时刻去重；偏离目标超过 ±2%（恰 2% 合格）记超差。
 */
public class BlendReading {

    private Long id;
    private String orderNo;
    /** 采样时生效的比例版本号。 */
    private Integer versionNo;
    /** 采样时刻，与订单号联合唯一去重。 */
    private LocalDateTime sampledAt;
    /** 实际掺配比例读数，单位 %。 */
    private BigDecimal actualRatioPct;
    /** 偏离目标 = |实测-目标|，单位 百分点。 */
    private BigDecimal deviationPct;
    /** 是否超差：true=偏离超过 ±2%，恰 2% 为 false。 */
    private boolean outOfTolerance;
    private String requestId;
    private LocalDateTime createdAt;

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

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public LocalDateTime getSampledAt() {
        return sampledAt;
    }

    public void setSampledAt(LocalDateTime sampledAt) {
        this.sampledAt = sampledAt;
    }

    public BigDecimal getActualRatioPct() {
        return actualRatioPct;
    }

    public void setActualRatioPct(BigDecimal actualRatioPct) {
        this.actualRatioPct = actualRatioPct;
    }

    public BigDecimal getDeviationPct() {
        return deviationPct;
    }

    public void setDeviationPct(BigDecimal deviationPct) {
        this.deviationPct = deviationPct;
    }

    public boolean isOutOfTolerance() {
        return outOfTolerance;
    }

    public void setOutOfTolerance(boolean outOfTolerance) {
        this.outOfTolerance = outOfTolerance;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
