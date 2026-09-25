package com.example.starter.greenhouse.po;

import java.time.Instant;

/**
 * 温室分区（策略下发、读数归属、能耗预算的最小单元）。
 */
public class Zone {
    private Long id;
    private String zoneCode;
    private String name;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getZoneCode() { return zoneCode; }
    public void setZoneCode(String zoneCode) { this.zoneCode = zoneCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
