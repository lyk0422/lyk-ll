package com.example.starter.greenhouse.po;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 封存后迟到读数事件（按 UTC 日界归属）。
 */
public class LateReadingEvent {
    private Long id;
    private Long zoneId;
    private Long readingId;
    private Instant sampleTime;
    private LocalDate budgetDay;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public Long getZoneId() { return zoneId; }
    public void setZoneId(Long v) { this.zoneId = v; }
    public Long getReadingId() { return readingId; }
    public void setReadingId(Long v) { this.readingId = v; }
    public Instant getSampleTime() { return sampleTime; }
    public void setSampleTime(Instant v) { this.sampleTime = v; }
    public LocalDate getBudgetDay() { return budgetDay; }
    public void setBudgetDay(LocalDate v) { this.budgetDay = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
