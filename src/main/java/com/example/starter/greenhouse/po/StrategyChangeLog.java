package com.example.starter.greenhouse.po;

import java.time.Instant;

/**
 * 策略变更流水：同分区相邻两条 effective_at 间隔不得少于 30 分钟。
 */
public class StrategyChangeLog {
    private Long id;
    private Long zoneId;
    private Long oldStrategyId;
    private Long newStrategyId;
    private Instant effectiveAt;
    private String requestId;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public Long getZoneId() { return zoneId; }
    public void setZoneId(Long v) { this.zoneId = v; }
    public Long getOldStrategyId() { return oldStrategyId; }
    public void setOldStrategyId(Long v) { this.oldStrategyId = v; }
    public Long getNewStrategyId() { return newStrategyId; }
    public void setNewStrategyId(Long v) { this.newStrategyId = v; }
    public Instant getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(Instant v) { this.effectiveAt = v; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String v) { this.requestId = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
