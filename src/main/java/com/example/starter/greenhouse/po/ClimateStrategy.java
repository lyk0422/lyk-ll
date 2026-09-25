package com.example.starter.greenhouse.po;

import com.example.starter.greenhouse.domain.StrategyStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 气候策略：昼/夜温湿度设定点与容差半带（℃ / %RH）。
 * 设定带为 [目标值-容差, 目标值+容差]，含边界。
 */
public class ClimateStrategy {
    private Long id;
    private String strategyCode;
    private Long cropId;
    private Long zoneId;
    private BigDecimal dayTempTarget;
    private BigDecimal dayTempTolerance;
    private BigDecimal dayHumidityTarget;
    private BigDecimal dayHumidityTolerance;
    private BigDecimal nightTempTarget;
    private BigDecimal nightTempTolerance;
    private BigDecimal nightHumidityTarget;
    private BigDecimal nightHumidityTolerance;
    private StrategyStatus status;
    private String validationMessage;
    private Instant createdAt;
    private Instant validatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStrategyCode() { return strategyCode; }
    public void setStrategyCode(String v) { this.strategyCode = v; }
    public Long getCropId() { return cropId; }
    public void setCropId(Long v) { this.cropId = v; }
    public Long getZoneId() { return zoneId; }
    public void setZoneId(Long v) { this.zoneId = v; }
    public BigDecimal getDayTempTarget() { return dayTempTarget; }
    public void setDayTempTarget(BigDecimal v) { this.dayTempTarget = v; }
    public BigDecimal getDayTempTolerance() { return dayTempTolerance; }
    public void setDayTempTolerance(BigDecimal v) { this.dayTempTolerance = v; }
    public BigDecimal getDayHumidityTarget() { return dayHumidityTarget; }
    public void setDayHumidityTarget(BigDecimal v) { this.dayHumidityTarget = v; }
    public BigDecimal getDayHumidityTolerance() { return dayHumidityTolerance; }
    public void setDayHumidityTolerance(BigDecimal v) { this.dayHumidityTolerance = v; }
    public BigDecimal getNightTempTarget() { return nightTempTarget; }
    public void setNightTempTarget(BigDecimal v) { this.nightTempTarget = v; }
    public BigDecimal getNightTempTolerance() { return nightTempTolerance; }
    public void setNightTempTolerance(BigDecimal v) { this.nightTempTolerance = v; }
    public BigDecimal getNightHumidityTarget() { return nightHumidityTarget; }
    public void setNightHumidityTarget(BigDecimal v) { this.nightHumidityTarget = v; }
    public BigDecimal getNightHumidityTolerance() { return nightHumidityTolerance; }
    public void setNightHumidityTolerance(BigDecimal v) { this.nightHumidityTolerance = v; }
    public StrategyStatus getStatus() { return status; }
    public void setStatus(StrategyStatus v) { this.status = v; }
    public String getValidationMessage() { return validationMessage; }
    public void setValidationMessage(String v) { this.validationMessage = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getValidatedAt() { return validatedAt; }
    public void setValidatedAt(Instant v) { this.validatedAt = v; }
}
