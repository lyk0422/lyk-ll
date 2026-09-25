package com.example.starter.greenhouse.po;

import java.time.Instant;

/**
 * 分区当前唯一生效策略（zone_id 主键保证同分区同时刻只有一个生效策略）。
 */
public class ZoneActiveStrategy {
    private Long zoneId;
    private Long strategyId;
    private Instant activatedAt;

    public Long getZoneId() { return zoneId; }
    public void setZoneId(Long v) { this.zoneId = v; }
    public Long getStrategyId() { return strategyId; }
    public void setStrategyId(Long v) { this.strategyId = v; }
    public Instant getActivatedAt() { return activatedAt; }
    public void setActivatedAt(Instant v) { this.activatedAt = v; }
}
