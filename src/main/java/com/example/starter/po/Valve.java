package com.example.starter.po;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单元入口阀门 PO（表 valve）。每个单元唯一对应一个阀门。
 */
public class Valve {
    private Long id;
    /** 所属单元ID */
    private Long unitId;
    /** 阀门编码 */
    private String valveCode;
    /** 当前开度百分比 0~100，单位:% */
    private BigDecimal opening;
    /** 开度最近变更时刻(UTC) */
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUnitId() { return unitId; }
    public void setUnitId(Long unitId) { this.unitId = unitId; }
    public String getValveCode() { return valveCode; }
    public void setValveCode(String valveCode) { this.valveCode = valveCode; }
    public BigDecimal getOpening() { return opening; }
    public void setOpening(BigDecimal opening) { this.opening = opening; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
