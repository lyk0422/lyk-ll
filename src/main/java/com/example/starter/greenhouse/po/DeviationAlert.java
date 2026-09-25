package com.example.starter.greenhouse.po;

import com.example.starter.greenhouse.domain.AlertStatus;
import java.time.Instant;

/**
 * 偏差告警：实际温湿度连续 3 个采样点（含第 3 点）偏离当前生效策略设定带时触发。
 */
public class DeviationAlert {
    private Long id;
    private Long zoneId;
    private Long strategyId;
    private AlertStatus status;
    private Instant firstDeviateTime;
    private Instant triggerSampleTime;
    private Instant recoveredAt;
    private Instant acknowledgedAt;
    private String ackRequestId;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public Long getZoneId() { return zoneId; }
    public void setZoneId(Long v) { this.zoneId = v; }
    public Long getStrategyId() { return strategyId; }
    public void setStrategyId(Long v) { this.strategyId = v; }
    public AlertStatus getStatus() { return status; }
    public void setStatus(AlertStatus v) { this.status = v; }
    public Instant getFirstDeviateTime() { return firstDeviateTime; }
    public void setFirstDeviateTime(Instant v) { this.firstDeviateTime = v; }
    public Instant getTriggerSampleTime() { return triggerSampleTime; }
    public void setTriggerSampleTime(Instant v) { this.triggerSampleTime = v; }
    public Instant getRecoveredAt() { return recoveredAt; }
    public void setRecoveredAt(Instant v) { this.recoveredAt = v; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(Instant v) { this.acknowledgedAt = v; }
    public String getAckRequestId() { return ackRequestId; }
    public void setAckRequestId(String v) { this.ackRequestId = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
