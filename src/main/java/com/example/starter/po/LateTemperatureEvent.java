package com.example.starter.po;

import java.time.Instant;

/**
 * 归档后迟到温度事件 PO（表 late_temperature_event）。
 * 任务归档终态后到达的温度数据只记录此事件，不参与任何评估。
 */
public class LateTemperatureEvent {
    private Long id;
    /** 换热站ID */
    private Long stationId;
    /** 单元ID，无法定位时为空 */
    private Long unitId;
    /** 迟到上报幂等键 */
    private String requestId;
    /** 迟到温度报文 JSON */
    private String detailJson;
    /** 温度测量时刻(UTC) */
    private Instant observedAt;
    /** 服务端接收时刻(UTC) */
    private Instant receivedAt;
    /** 落库时刻(UTC) */
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStationId() { return stationId; }
    public void setStationId(Long stationId) { this.stationId = stationId; }
    public Long getUnitId() { return unitId; }
    public void setUnitId(Long unitId) { this.unitId = unitId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }
    public Instant getObservedAt() { return observedAt; }
    public void setObservedAt(Instant observedAt) { this.observedAt = observedAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
