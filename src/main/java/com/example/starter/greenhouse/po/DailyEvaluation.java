package com.example.starter.greenhouse.po;

import com.example.starter.greenhouse.domain.EvaluationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 分区单日（UTC 日界）评估结果。OPEN 可复算；SEALED 为封存终态。
 */
public class DailyEvaluation {
    private Long id;
    private Long zoneId;
    private LocalDate budgetDay;
    private EvaluationStatus status;
    private int totalReadings;
    private int achievedReadings;
    private BigDecimal achievementRate;
    private BigDecimal totalKwh;
    private BigDecimal heatingKwh;
    private BigDecimal lightingKwh;
    private int ventilationCount;
    private Instant sealedAt;
    private String detailJson;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public Long getZoneId() { return zoneId; }
    public void setZoneId(Long v) { this.zoneId = v; }
    public LocalDate getBudgetDay() { return budgetDay; }
    public void setBudgetDay(LocalDate v) { this.budgetDay = v; }
    public EvaluationStatus getStatus() { return status; }
    public void setStatus(EvaluationStatus v) { this.status = v; }
    public int getTotalReadings() { return totalReadings; }
    public void setTotalReadings(int v) { this.totalReadings = v; }
    public int getAchievedReadings() { return achievedReadings; }
    public void setAchievedReadings(int v) { this.achievedReadings = v; }
    public BigDecimal getAchievementRate() { return achievementRate; }
    public void setAchievementRate(BigDecimal v) { this.achievementRate = v; }
    public BigDecimal getTotalKwh() { return totalKwh; }
    public void setTotalKwh(BigDecimal v) { this.totalKwh = v; }
    public BigDecimal getHeatingKwh() { return heatingKwh; }
    public void setHeatingKwh(BigDecimal v) { this.heatingKwh = v; }
    public BigDecimal getLightingKwh() { return lightingKwh; }
    public void setLightingKwh(BigDecimal v) { this.lightingKwh = v; }
    public int getVentilationCount() { return ventilationCount; }
    public void setVentilationCount(int v) { this.ventilationCount = v; }
    public Instant getSealedAt() { return sealedAt; }
    public void setSealedAt(Instant v) { this.sealedAt = v; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String v) { this.detailJson = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
