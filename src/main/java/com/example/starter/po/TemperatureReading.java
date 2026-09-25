package com.example.starter.po;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 温度采集数据 PO（表 temperature_reading）。
 */
public class TemperatureReading {
    private Long id;
    /** 所属单元ID */
    private Long unitId;
    /** 所属换热站ID */
    private Long stationId;
    /** 供水温度，单位:℃ */
    private BigDecimal supplyTemp;
    /** 回水温度，单位:℃ */
    private BigDecimal returnTemp;
    /** 温度测量时刻(UTC) */
    private Instant observedAt;
    /** 服务端接收时刻(UTC) */
    private Instant receivedAt;
    /** 落库时刻(UTC) */
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUnitId() { return unitId; }
    public void setUnitId(Long unitId) { this.unitId = unitId; }
    public Long getStationId() { return stationId; }
    public void setStationId(Long stationId) { this.stationId = stationId; }
    public BigDecimal getSupplyTemp() { return supplyTemp; }
    public void setSupplyTemp(BigDecimal supplyTemp) { this.supplyTemp = supplyTemp; }
    public BigDecimal getReturnTemp() { return returnTemp; }
    public void setReturnTemp(BigDecimal returnTemp) { this.returnTemp = returnTemp; }
    public Instant getObservedAt() { return observedAt; }
    public void setObservedAt(Instant observedAt) { this.observedAt = observedAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
