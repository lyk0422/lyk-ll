package com.example.starter.greenhouse.po;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 分区单日（UTC 日界）能耗预算与剩余额度（kWh），remaining 不允许为负。
 */
public class EnergyBudget {
    private Long id;
    private Long zoneId;
    private LocalDate budgetDay;
    private BigDecimal budgetKwh;
    private BigDecimal remainingKwh;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public Long getZoneId() { return zoneId; }
    public void setZoneId(Long v) { this.zoneId = v; }
    public LocalDate getBudgetDay() { return budgetDay; }
    public void setBudgetDay(LocalDate v) { this.budgetDay = v; }
    public BigDecimal getBudgetKwh() { return budgetKwh; }
    public void setBudgetKwh(BigDecimal v) { this.budgetKwh = v; }
    public BigDecimal getRemainingKwh() { return remainingKwh; }
    public void setRemainingKwh(BigDecimal v) { this.remainingKwh = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
