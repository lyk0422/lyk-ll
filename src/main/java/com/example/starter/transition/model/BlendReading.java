package com.example.starter.transition.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 实际掺配读数：按订单与采样时刻去重。
 */
public class BlendReading {

    private Long id;
    private String orderNo;
    /** 读数所属比例版本号（登记时生效版本）。 */
    private Integer versionNo;
    /** 采样时刻，与订单号联合唯一去重。 */
    private LocalDateTime sampledAt;
    /** 实际掺配读数，单位 %。 */
    private BigDecimal actualRatioPct;
    /** 相对目标比例偏差绝对值，单位 %；恰 2.0000% 合格。 */
    private BigDecimal deviationPct;
    /** 是否在 ±2% 容差内。 */
    private boolean withinTolerance;
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

    public boolean isWithinTolerance() {
        return withinTolerance;
    }

    public void setWithinTolerance(boolean withinTolerance) {
        this.withinTolerance = withinTolerance;
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
