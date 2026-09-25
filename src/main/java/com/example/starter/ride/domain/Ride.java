package com.example.starter.ride.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 设备当前状态行，同一设备同时刻只有一条状态链。
 */
public class Ride {

    private String id;
    private String name;
    /** 载荷阈值，单位 kg。 */
    private double loadThreshold;
    /** 速度阈值，单位 m/s。 */
    private double speedThreshold;
    private String status;
    private LocalDate bizDate;
    private boolean dailyCheckPassed;
    private int trialPassedCount;
    private int reopenCount;
    private int openSeq;
    private Instant archivedAt;
    private Instant updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getLoadThreshold() {
        return loadThreshold;
    }

    public void setLoadThreshold(double loadThreshold) {
        this.loadThreshold = loadThreshold;
    }

    public double getSpeedThreshold() {
        return speedThreshold;
    }

    public void setSpeedThreshold(double speedThreshold) {
        this.speedThreshold = speedThreshold;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDate getBizDate() {
        return bizDate;
    }

    public void setBizDate(LocalDate bizDate) {
        this.bizDate = bizDate;
    }

    public boolean isDailyCheckPassed() {
        return dailyCheckPassed;
    }

    public void setDailyCheckPassed(boolean dailyCheckPassed) {
        this.dailyCheckPassed = dailyCheckPassed;
    }

    public int getTrialPassedCount() {
        return trialPassedCount;
    }

    public void setTrialPassedCount(int trialPassedCount) {
        this.trialPassedCount = trialPassedCount;
    }

    public int getReopenCount() {
        return reopenCount;
    }

    public void setReopenCount(int reopenCount) {
        this.reopenCount = reopenCount;
    }

    public int getOpenSeq() {
        return openSeq;
    }

    public void setOpenSeq(int openSeq) {
        this.openSeq = openSeq;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
