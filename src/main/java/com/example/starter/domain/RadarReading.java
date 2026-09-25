package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 雷达位移读数。按“测区＋readingTime”唯一去重，乱序上报按 readingTime 归位复算。
 * displacementMm：该时刻累计位移，毫米。
 * lateFlag：归档后迟到来的数据置 1，只记迟到事件，不影响已归档处置链。
 */
public class RadarReading {

    private Long id;
    private String zoneCode;
    private LocalDateTime readingTime;
    private BigDecimal displacementMm;
    private boolean lateFlag;
    private LocalDateTime receivedAt;

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

    public LocalDateTime getReadingTime() {
        return readingTime;
    }

    public void setReadingTime(LocalDateTime readingTime) {
        this.readingTime = readingTime;
    }

    public BigDecimal getDisplacementMm() {
        return displacementMm;
    }

    public void setDisplacementMm(BigDecimal displacementMm) {
        this.displacementMm = displacementMm;
    }

    public boolean isLateFlag() {
        return lateFlag;
    }

    public void setLateFlag(boolean lateFlag) {
        this.lateFlag = lateFlag;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }
}
