package com.example.starter.po;

import java.time.Instant;

/**
 * 换热单元 PO（表 heating_unit）。
 */
public class HeatingUnit {
    private Long id;
    /** 所属换热站ID */
    private Long stationId;
    /** 单元编码 */
    private String unitCode;
    /** 单元名称 */
    private String unitName;
    /** 创建时刻(UTC) */
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStationId() { return stationId; }
    public void setStationId(Long stationId) { this.stationId = stationId; }
    public String getUnitCode() { return unitCode; }
    public void setUnitCode(String unitCode) { this.unitCode = unitCode; }
    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
