package com.example.starter.po;

import java.time.Instant;

/**
 * 换热站 PO（表 heat_station）。
 */
public class HeatStation {
    private Long id;
    /** 换热站编码 */
    private String stationCode;
    /** 换热站名称 */
    private String stationName;
    /** 创建时刻(UTC) */
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStationCode() { return stationCode; }
    public void setStationCode(String stationCode) { this.stationCode = stationCode; }
    public String getStationName() { return stationName; }
    public void setStationName(String stationName) { this.stationName = stationName; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
