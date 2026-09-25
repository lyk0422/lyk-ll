package com.example.starter.greenhouse.po;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 传感器读数：按“分区 + 采样时刻(UTC)”唯一去重，乱序上报按采样时刻归位。
 * lateFlag=true 表示封存后迟到，仅登记迟到事件，不进入评估统计。
 */
public class SensorReading {
    private Long id;
    private Long zoneId;
    private Instant sampleTime;
    private BigDecimal temperature;
    private BigDecimal humidity;
    private boolean lateFlag;
    private Instant ingestedAt;

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public Long getZoneId() { return zoneId; }
    public void setZoneId(Long v) { this.zoneId = v; }
    public Instant getSampleTime() { return sampleTime; }
    public void setSampleTime(Instant v) { this.sampleTime = v; }
    public BigDecimal getTemperature() { return temperature; }
    public void setTemperature(BigDecimal v) { this.temperature = v; }
    public BigDecimal getHumidity() { return humidity; }
    public void setHumidity(BigDecimal v) { this.humidity = v; }
    public boolean isLateFlag() { return lateFlag; }
    public void setLateFlag(boolean v) { this.lateFlag = v; }
    public Instant getIngestedAt() { return ingestedAt; }
    public void setIngestedAt(Instant v) { this.ingestedAt = v; }
}
