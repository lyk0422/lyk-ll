package com.example.starter.greenhouse.po;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 作物及其当前生育期温湿度允许窗口（℃ / %RH，含边界）。
 */
public class Crop {
    private Long id;
    private String cropCode;
    private String name;
    private String stageCode;
    private BigDecimal dayTempMin;
    private BigDecimal dayTempMax;
    private BigDecimal dayHumidityMin;
    private BigDecimal dayHumidityMax;
    private BigDecimal nightTempMin;
    private BigDecimal nightTempMax;
    private BigDecimal nightHumidityMin;
    private BigDecimal nightHumidityMax;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCropCode() { return cropCode; }
    public void setCropCode(String cropCode) { this.cropCode = cropCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStageCode() { return stageCode; }
    public void setStageCode(String stageCode) { this.stageCode = stageCode; }
    public BigDecimal getDayTempMin() { return dayTempMin; }
    public void setDayTempMin(BigDecimal dayTempMin) { this.dayTempMin = dayTempMin; }
    public BigDecimal getDayTempMax() { return dayTempMax; }
    public void setDayTempMax(BigDecimal dayTempMax) { this.dayTempMax = dayTempMax; }
    public BigDecimal getDayHumidityMin() { return dayHumidityMin; }
    public void setDayHumidityMin(BigDecimal v) { this.dayHumidityMin = v; }
    public BigDecimal getDayHumidityMax() { return dayHumidityMax; }
    public void setDayHumidityMax(BigDecimal v) { this.dayHumidityMax = v; }
    public BigDecimal getNightTempMin() { return nightTempMin; }
    public void setNightTempMin(BigDecimal v) { this.nightTempMin = v; }
    public BigDecimal getNightTempMax() { return nightTempMax; }
    public void setNightTempMax(BigDecimal v) { this.nightTempMax = v; }
    public BigDecimal getNightHumidityMin() { return nightHumidityMin; }
    public void setNightHumidityMin(BigDecimal v) { this.nightHumidityMin = v; }
    public BigDecimal getNightHumidityMax() { return nightHumidityMax; }
    public void setNightHumidityMax(BigDecimal v) { this.nightHumidityMax = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
