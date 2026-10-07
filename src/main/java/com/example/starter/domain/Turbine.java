package com.example.starter.domain;

import java.time.Instant;

/**
 * 风电机组。
 * ratedPowerKw 装机容量（kW）；technicalMinKw 技术出力下限（kW，额定的 15%）；
 * availablePowerKw 最新可用出力（kW），随执行回报刷新；
 * status 可用状态（AVAILABLE/UNAVAILABLE），仅受控分段计划每分钟重查，旧入口不读取。
 */
public class Turbine {

    private Long id;
    private String farmCode;
    private String turbineCode;
    private int ratedPowerKw;
    private int technicalMinKw;
    private int availablePowerKw;
    private Instant updatedAt;
    private String status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFarmCode() {
        return farmCode;
    }

    public void setFarmCode(String farmCode) {
        this.farmCode = farmCode;
    }

    public String getTurbineCode() {
        return turbineCode;
    }

    public void setTurbineCode(String turbineCode) {
        this.turbineCode = turbineCode;
    }

    public int getRatedPowerKw() {
        return ratedPowerKw;
    }

    public void setRatedPowerKw(int ratedPowerKw) {
        this.ratedPowerKw = ratedPowerKw;
    }

    public int getTechnicalMinKw() {
        return technicalMinKw;
    }

    public void setTechnicalMinKw(int technicalMinKw) {
        this.technicalMinKw = technicalMinKw;
    }

    public int getAvailablePowerKw() {
        return availablePowerKw;
    }

    public void setAvailablePowerKw(int availablePowerKw) {
        this.availablePowerKw = availablePowerKw;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
