package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 监测测区。
 * productionStatus：ACTIVE 生产中 / STOPPED 已停产 / RESUMED 已复工（三者互斥终态）。
 * operationLocked：作业封锁，红色预警撤人确认事务内置 1，复工批准事务内清零。
 * 速率阈值单位：毫米/小时（warnHourlyRate、safetyHourlyRate）、毫米/日（alarmDailyRate）；恰等阈值判正常。
 */
public class MonitorZone {

    private Long id;
    private String zoneCode;
    private String zoneName;
    private BigDecimal warnHourlyRate;
    private BigDecimal alarmDailyRate;
    private BigDecimal safetyHourlyRate;
    private String productionStatus;
    private boolean operationLocked;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getZoneCode() {
        return zoneCode;
    }

    public void setZoneCode(String zoneCode) {
        this.zoneCode = zoneCode;
    }

    public String getZoneName() {
        return zoneName;
    }

    public void setZoneName(String zoneName) {
        this.zoneName = zoneName;
    }

    public BigDecimal getWarnHourlyRate() {
        return warnHourlyRate;
    }

    public void setWarnHourlyRate(BigDecimal warnHourlyRate) {
        this.warnHourlyRate = warnHourlyRate;
    }

    public BigDecimal getAlarmDailyRate() {
        return alarmDailyRate;
    }

    public void setAlarmDailyRate(BigDecimal alarmDailyRate) {
        this.alarmDailyRate = alarmDailyRate;
    }

    public BigDecimal getSafetyHourlyRate() {
        return safetyHourlyRate;
    }

    public void setSafetyHourlyRate(BigDecimal safetyHourlyRate) {
        this.safetyHourlyRate = safetyHourlyRate;
    }

    public String getProductionStatus() {
        return productionStatus;
    }

    public void setProductionStatus(String productionStatus) {
        this.productionStatus = productionStatus;
    }

    public boolean isOperationLocked() {
        return operationLocked;
    }

    public void setOperationLocked(boolean operationLocked) {
        this.operationLocked = operationLocked;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
